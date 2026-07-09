package com.ihrms.mail;

import com.ihrms.audit.AuditActor;
import com.ihrms.audit.AuditService;
import com.ihrms.auth.AuthorizationService;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Message;
import com.ihrms.domain.model.MessageRecipient;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.MessageRecipientRepository;
import com.ihrms.domain.repository.MessageRepository;
import com.ihrms.domain.repository.UserRepository;
import com.ihrms.domain.support.Cuids;
import com.ihrms.mail.dto.MailDtos.MailPartyView;
import com.ihrms.mail.dto.MailDtos.ReplyRequest;
import com.ihrms.mail.dto.MailDtos.SendMessageRequest;
import com.ihrms.mail.dto.MailDtos.SendMessageResult;
import com.ihrms.mail.dto.MailDtos.ThreadDetailView;
import com.ihrms.mail.dto.MailDtos.ThreadListItemView;
import com.ihrms.mail.dto.MailDtos.ThreadMessageView;
import com.ihrms.mail.dto.MailDtos.ThreadPage;
import com.ihrms.mail.dto.MailDtos.UnreadCountView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Internal mail (ARCHITECTURE.md §8) — messaging between IHRMS staff accounts, organised into
 * <b>threads</b> (Stage 3). A message is DB rows, never external email. Every send AND every reply is
 * gated by the single authorization graph ({@link AuthorizationService#canSendMail}); nothing here
 * reaches across companies. Delete is a per-user soft-hide (the row is never destroyed; the counterparty
 * keeps their copy). Addresses are cosmetic display handles resolved from the account's role/company.
 */
@Service
public class InternalMailService {

  /** Accounts on the platform domain ({@code @ihrms}) rather than a company domain. */
  private static final EnumSet<UserRole> PLATFORM =
      EnumSet.of(UserRole.SUPER_ADMIN, UserRole.ACCOUNTS_ADMIN);

  private static final int SNIPPET = 140;

  private final UserRepository users;
  private final CompanyRepository companies;
  private final MessageRepository messages;
  private final MessageRecipientRepository recipients;
  private final AuthorizationService authz;
  private final AuditService audit;

  public InternalMailService(
      UserRepository users,
      CompanyRepository companies,
      MessageRepository messages,
      MessageRecipientRepository recipients,
      AuthorizationService authz,
      AuditService audit) {
    this.users = users;
    this.companies = companies;
    this.messages = messages;
    this.recipients = recipients;
    this.authz = authz;
    this.audit = audit;
  }

  // --- Contacts -----------------------------------------------------------------

  /** The accounts the caller may message — the send graph, filtered down to a concrete list (§8). */
  @Transactional(readOnly = true)
  public List<MailPartyView> contacts(IhrmsPrincipal.User actor) {
    User me = requireUser(actor.userId());

    // Narrow the candidate pool to only accounts the graph could ever connect to, then apply the
    // authoritative check — so the UI list and the send-time gate can never disagree.
    Set<User> candidates = new HashSet<>();
    if (me.getCompanyId() != null) {
      candidates.addAll(users.findByCompanyId(me.getCompanyId()));
      candidates.addAll(users.findByRoleIn(List.of(UserRole.SUPER_ADMIN, UserRole.ACCOUNTS_ADMIN)));
    } else {
      candidates.addAll(
          users.findByRoleIn(
              List.of(UserRole.SUPER_ADMIN, UserRole.ACCOUNTS_ADMIN, UserRole.COMPANY_ADMIN)));
    }

    List<User> allowed =
        candidates.stream().filter(c -> authz.canSendMail(me, c)).collect(Collectors.toList());
    Map<String, String> domains = domainsFor(allowed);
    return allowed.stream()
        .map(u -> party(u, domains))
        .sorted(Comparator.comparing(MailPartyView::address))
        .collect(Collectors.toList());
  }

  // --- Send (new thread) + Reply (join a thread) --------------------------------

  /** Start a NEW thread to one recipient. Rejects (403) anything the send graph forbids. Audited. */
  @Transactional
  public SendMessageResult send(IhrmsPrincipal.User actor, SendMessageRequest input, String ip) {
    User me = requireUser(actor.userId());
    User to =
        users
            .findById(input.toUserId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Recipient not found"));
    if (!authz.canSendMail(me, to)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot message this recipient");
    }

    String threadId = Cuids.newId(); // a fresh conversation
    Message message = new Message();
    message.setThreadId(threadId);
    message.setSenderUserId(me.getId());
    message.setSubject(input.subject().strip());
    message.setBody(input.body());
    messages.save(message);
    saveRecipient(message.getId(), to.getId());

    auditSent(me, message, to.getId(), ip);
    return new SendMessageResult(message.getId(), threadId);
  }

  /**
   * Reply within a thread. The recipient is DERIVED from the thread (the other participant) — never
   * supplied by the caller — and a reply IS a send, so {@link AuthorizationService#canSendMail} is
   * re-checked here (403 if the graph now forbids it). Audited {@code MAIL_SENT}.
   */
  @Transactional
  public SendMessageResult reply(IhrmsPrincipal.User actor, String threadId, ReplyRequest input, String ip) {
    User me = requireUser(actor.userId());
    List<Message> msgs = messages.findByThreadIdOrderByCreatedAtAsc(threadId);
    if (msgs.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Thread not found");
    }
    Map<String, List<MessageRecipient>> recByMsg = recipientsByMessage(msgs);
    if (!isParticipant(me, msgs, recByMsg)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You are not part of this conversation");
    }

    String otherId = counterpartyId(me, msgs, recByMsg);
    if (otherId == null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "This conversation has no recipient to reply to");
    }
    User to =
        users
            .findById(otherId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Recipient not found"));
    // The graph still governs replies — re-check every time (roles/companies can change mid-thread).
    if (!authz.canSendMail(me, to)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot message this recipient");
    }

    Message reply = new Message();
    reply.setThreadId(threadId);
    reply.setParentMessageId(msgs.get(msgs.size() - 1).getId());
    reply.setSenderUserId(me.getId());
    reply.setSubject(msgs.get(0).getSubject()); // the thread keeps its subject
    reply.setBody(input.body());
    messages.save(reply);
    saveRecipient(reply.getId(), to.getId());

    auditSent(me, reply, to.getId(), ip);
    return new SendMessageResult(reply.getId(), threadId);
  }

  // --- Lists: Inbox / Sent / Search (all THREADS) -------------------------------

  /** Inbox as threads: conversations with a message addressed to the caller, newest activity first. */
  @Transactional(readOnly = true)
  public ThreadPage inbox(IhrmsPrincipal.User actor, Pageable pageable) {
    User me = requireUser(actor.userId());
    Page<String> ids = messages.findInboxThreadIds(me.getId(), pageable);
    return page(ids, buildThreadList(me, ids.getContent()));
  }

  /** Sent as threads: conversations the caller has sent into. */
  @Transactional(readOnly = true)
  public ThreadPage sent(IhrmsPrincipal.User actor, Pageable pageable) {
    User me = requireUser(actor.userId());
    Page<String> ids = messages.findSentThreadIds(me.getId(), pageable);
    return page(ids, buildThreadList(me, ids.getContent()));
  }

  /** Search the caller's OWN mail (subject + body, case-insensitive). Never another mailbox (§8). */
  @Transactional(readOnly = true)
  public ThreadPage search(IhrmsPrincipal.User actor, String q, Pageable pageable) {
    User me = requireUser(actor.userId());
    String term = q == null ? "" : q.strip().toLowerCase();
    if (term.isEmpty()) {
      return new ThreadPage(List.of(), 0, pageable.getPageSize(), 0, 0);
    }
    Page<String> ids = messages.searchThreadIds(me.getId(), "%" + term + "%", pageable);
    return page(ids, buildThreadList(me, ids.getContent()));
  }

  // --- Open one thread ----------------------------------------------------------

  /**
   * Open a thread: its messages chronologically, plus the counterparty a reply would go to. Only a
   * participant may open it (else 403). Marks the caller's unread messages read; audited {@code MAIL_VIEWED}.
   */
  @Transactional
  public ThreadDetailView thread(IhrmsPrincipal.User actor, String threadId, String ip) {
    User me = requireUser(actor.userId());
    List<Message> msgs = messages.findByThreadIdOrderByCreatedAtAsc(threadId);
    if (msgs.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Thread not found");
    }
    Map<String, List<MessageRecipient>> recByMsg = recipientsByMessage(msgs);
    Map<String, MessageRecipient> mine = myRows(me, recByMsg);
    if (!isParticipant(me, msgs, recByMsg)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot view this conversation");
    }

    List<Message> visible = msgs.stream().filter(m -> visibleToMe(me, m, mine)).toList();
    if (visible.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Thread not found");
    }

    // Mark my unread (non-deleted) messages in this thread read.
    List<MessageRecipient> toStamp =
        mine.values().stream().filter(r -> r.getReadAt() == null && r.getDeletedAt() == null).toList();
    Instant now = Instant.now();
    toStamp.forEach(r -> r.setReadAt(now));
    if (!toStamp.isEmpty()) {
      recipients.saveAll(toStamp);
    }

    Map<String, User> userMap = usersById(participantIds(msgs, recByMsg, null));
    Map<String, String> domains = domainsFor(userMap.values());

    List<ThreadMessageView> messageViews =
        visible.stream()
            .map(
                m ->
                    new ThreadMessageView(
                        m.getId(),
                        party(userMap.get(m.getSenderUserId()), domains),
                        m.getBody(),
                        m.getCreatedAt().toString(),
                        m.getSenderUserId().equals(me.getId())))
            .toList();
    List<MailPartyView> participants = otherParties(me, msgs, recByMsg, userMap, domains);
    String otherId = counterpartyId(me, msgs, recByMsg);
    MailPartyView counterparty = otherId == null ? null : party(userMap.get(otherId), domains);

    audit.record(
        new AuditActor("USER", me.getId(), me.getCompanyId()),
        "MAIL_VIEWED",
        "Thread",
        threadId,
        Map.of("messageCount", visible.size()),
        ip);

    return new ThreadDetailView(
        threadId, msgs.get(0).getSubject(), participants, counterparty, messageViews);
  }

  // --- Read state ---------------------------------------------------------------

  /** Mark a whole thread read for the caller; returns the refreshed unread-thread count. */
  @Transactional
  public UnreadCountView markRead(IhrmsPrincipal.User actor, String threadId) {
    setReadState(actor, threadId, true);
    return unreadCount(actor);
  }

  /** Mark a whole thread unread for the caller; returns the refreshed unread-thread count. */
  @Transactional
  public UnreadCountView markUnread(IhrmsPrincipal.User actor, String threadId) {
    setReadState(actor, threadId, false);
    return unreadCount(actor);
  }

  /** Thread-level unread badge (§8): how many threads have an unopened message for the caller. */
  @Transactional(readOnly = true)
  public UnreadCountView unreadCount(IhrmsPrincipal.User actor) {
    return new UnreadCountView(messages.countUnreadThreads(actor.userId()));
  }

  // --- Delete (per-user soft-hide) ----------------------------------------------

  /**
   * Delete a thread for the caller ONLY (§8): soft-hide their own copies — the message rows are never
   * destroyed and the counterparty still sees the conversation. Idempotent; audited {@code MAIL_DELETED}.
   */
  @Transactional
  public void delete(IhrmsPrincipal.User actor, String threadId, String ip) {
    User me = requireUser(actor.userId());
    List<Message> msgs = messages.findByThreadIdOrderByCreatedAtAsc(threadId);
    if (msgs.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Thread not found");
    }
    Map<String, List<MessageRecipient>> recByMsg = recipientsByMessage(msgs);
    Map<String, MessageRecipient> mine = myRows(me, recByMsg);
    if (!isParticipant(me, msgs, recByMsg)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot delete this conversation");
    }
    Instant now = Instant.now();

    List<MessageRecipient> hideReceived =
        mine.values().stream().filter(r -> r.getDeletedAt() == null).toList();
    hideReceived.forEach(r -> r.setDeletedAt(now));
    if (!hideReceived.isEmpty()) {
      recipients.saveAll(hideReceived);
    }

    List<Message> hideSent =
        msgs.stream()
            .filter(m -> m.getSenderUserId().equals(me.getId()) && m.getSenderDeletedAt() == null)
            .toList();
    hideSent.forEach(m -> m.setSenderDeletedAt(now));
    if (!hideSent.isEmpty()) {
      messages.saveAll(hideSent);
    }

    audit.record(
        new AuditActor("USER", me.getId(), me.getCompanyId()),
        "MAIL_DELETED",
        "Thread",
        threadId,
        Map.of("hiddenMessages", hideReceived.size() + hideSent.size()),
        ip);
  }

  // --- Thread building ----------------------------------------------------------

  private ThreadPage page(Page<String> ids, List<ThreadListItemView> content) {
    return new ThreadPage(
        content, ids.getNumber(), ids.getSize(), ids.getTotalElements(), ids.getTotalPages());
  }

  /** Build the list rows for a page of threadIds, from the viewer's perspective (batched, no N+1). */
  private List<ThreadListItemView> buildThreadList(User me, List<String> threadIds) {
    if (threadIds.isEmpty()) {
      return List.of();
    }
    List<Message> all = messages.findByThreadIdInOrderByThreadIdAscCreatedAtAsc(threadIds);
    Map<String, List<Message>> byThread = new LinkedHashMap<>();
    for (Message m : all) {
      byThread.computeIfAbsent(m.getThreadId(), k -> new ArrayList<>()).add(m);
    }
    Map<String, List<MessageRecipient>> recByMsg = recipientsByMessage(all);
    Map<String, MessageRecipient> mine = myRows(me, recByMsg);

    Map<String, User> userMap = usersById(participantIds(all, recByMsg, null));
    Map<String, String> domains = domainsFor(userMap.values());

    List<ThreadListItemView> out = new ArrayList<>();
    for (String threadId : threadIds) {
      List<Message> msgs = byThread.get(threadId);
      if (msgs == null || msgs.isEmpty()) {
        continue;
      }
      List<Message> visible = msgs.stream().filter(m -> visibleToMe(me, m, mine)).toList();
      if (visible.isEmpty()) {
        continue; // fully hidden by me — defensively skip (shouldn't appear in the id page)
      }
      Message last = visible.get(visible.size() - 1);
      boolean unread =
          visible.stream()
              .anyMatch(
                  m ->
                      !m.getSenderUserId().equals(me.getId())
                          && mine.get(m.getId()) != null
                          && mine.get(m.getId()).getReadAt() == null);
      List<MailPartyView> participants = otherParties(me, msgs, recByMsg, userMap, domains);
      out.add(
          new ThreadListItemView(
              threadId,
              msgs.get(0).getSubject(),
              snippet(last.getBody()),
              last.getCreatedAt().toString(),
              participants,
              visible.size(),
              unread));
    }
    return out;
  }

  private void setReadState(IhrmsPrincipal.User actor, String threadId, boolean read) {
    User me = requireUser(actor.userId());
    List<Message> msgs = messages.findByThreadIdOrderByCreatedAtAsc(threadId);
    if (msgs.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Thread not found");
    }
    Map<String, List<MessageRecipient>> recByMsg = recipientsByMessage(msgs);
    Map<String, MessageRecipient> mine = myRows(me, recByMsg);
    if (!isParticipant(me, msgs, recByMsg)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You are not part of this conversation");
    }
    Instant now = Instant.now();
    List<MessageRecipient> toUpdate =
        mine.values().stream().filter(r -> r.getDeletedAt() == null).toList();
    toUpdate.forEach(r -> r.setReadAt(read ? now : null));
    if (!toUpdate.isEmpty()) {
      recipients.saveAll(toUpdate);
    }
  }

  // --- Participants / counterparty ----------------------------------------------

  private boolean isParticipant(
      User me, List<Message> msgs, Map<String, List<MessageRecipient>> recByMsg) {
    for (Message m : msgs) {
      if (m.getSenderUserId().equals(me.getId())) {
        return true;
      }
      for (MessageRecipient r : recByMsg.getOrDefault(m.getId(), List.of())) {
        if (r.getRecipientUserId().equals(me.getId())) {
          return true;
        }
      }
    }
    return false;
  }

  /** The other party a reply goes to — derived from the thread's first message (2-party in v1). */
  private String counterpartyId(
      User me, List<Message> msgs, Map<String, List<MessageRecipient>> recByMsg) {
    Message root = msgs.get(0);
    if (!root.getSenderUserId().equals(me.getId())) {
      return root.getSenderUserId();
    }
    for (MessageRecipient r : recByMsg.getOrDefault(root.getId(), List.of())) {
      if (!r.getRecipientUserId().equals(me.getId())) {
        return r.getRecipientUserId();
      }
    }
    // Fallback: any other participant across the thread.
    for (String id : participantIds(msgs, recByMsg, me.getId())) {
      return id;
    }
    return null;
  }

  /** All participant user ids across the thread (senders + recipients), optionally excluding {@code exclude}. */
  private Set<String> participantIds(
      List<Message> msgs, Map<String, List<MessageRecipient>> recByMsg, String exclude) {
    Set<String> ids = new LinkedHashSet<>();
    for (Message m : msgs) {
      if (exclude == null || !m.getSenderUserId().equals(exclude)) {
        ids.add(m.getSenderUserId());
      }
      for (MessageRecipient r : recByMsg.getOrDefault(m.getId(), List.of())) {
        if (exclude == null || !r.getRecipientUserId().equals(exclude)) {
          ids.add(r.getRecipientUserId());
        }
      }
    }
    return ids;
  }

  private List<MailPartyView> otherParties(
      User me,
      List<Message> msgs,
      Map<String, List<MessageRecipient>> recByMsg,
      Map<String, User> userMap,
      Map<String, String> domains) {
    return participantIds(msgs, recByMsg, me.getId()).stream()
        .map(id -> party(userMap.get(id), domains))
        .collect(Collectors.toList());
  }

  private boolean visibleToMe(User me, Message m, Map<String, MessageRecipient> mine) {
    if (m.getSenderUserId().equals(me.getId())) {
      return m.getSenderDeletedAt() == null;
    }
    MessageRecipient r = mine.get(m.getId());
    return r != null && r.getDeletedAt() == null;
  }

  private Map<String, List<MessageRecipient>> recipientsByMessage(List<Message> msgs) {
    List<String> ids = msgs.stream().map(Message::getId).toList();
    Map<String, List<MessageRecipient>> map = new HashMap<>();
    for (MessageRecipient r : recipients.findByMessageIdIn(ids)) {
      map.computeIfAbsent(r.getMessageId(), k -> new ArrayList<>()).add(r);
    }
    return map;
  }

  private Map<String, MessageRecipient> myRows(User me, Map<String, List<MessageRecipient>> recByMsg) {
    Map<String, MessageRecipient> mine = new HashMap<>();
    for (List<MessageRecipient> rs : recByMsg.values()) {
      for (MessageRecipient r : rs) {
        if (r.getRecipientUserId().equals(me.getId())) {
          mine.put(r.getMessageId(), r);
        }
      }
    }
    return mine;
  }

  // --- Small helpers ------------------------------------------------------------

  private void saveRecipient(String messageId, String recipientUserId) {
    MessageRecipient row = new MessageRecipient();
    row.setMessageId(messageId);
    row.setRecipientUserId(recipientUserId);
    recipients.save(row);
  }

  private void auditSent(User me, Message message, String toUserId, String ip) {
    // Attribute to the sender's company (portal-level for SUPER_ADMIN); mail crosses portal↔company,
    // never company↔company.
    audit.record(
        new AuditActor("USER", me.getId(), me.getCompanyId()),
        "MAIL_SENT",
        "Message",
        message.getId(),
        Map.of("threadId", message.getThreadId(), "toUserId", toUserId, "subject", message.getSubject()),
        ip);
  }

  private static String snippet(String body) {
    String s = body == null ? "" : body.strip().replaceAll("\\s+", " ");
    return s.length() <= SNIPPET ? s : s.substring(0, SNIPPET - 1) + "…";
  }

  private User requireUser(String id) {
    return users
        .findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unknown account"));
  }

  /** Company mailDomain by companyId, for every company-scoped user in the set (batched, no N+1). */
  private Map<String, String> domainsFor(Iterable<User> people) {
    Set<String> companyIds = new HashSet<>();
    for (User u : people) {
      if (u != null && u.getCompanyId() != null && !PLATFORM.contains(u.getRole())) {
        companyIds.add(u.getCompanyId());
      }
    }
    Map<String, String> domains = new HashMap<>();
    if (!companyIds.isEmpty()) {
      for (Company c : companies.findAllById(companyIds)) {
        domains.put(c.getId(), c.getMailDomain());
      }
    }
    return domains;
  }

  /** The display address {@code localpart@domain} — platform accounts on {@code @ihrms}, else company. */
  private MailPartyView party(User u, Map<String, String> domains) {
    if (u == null) {
      return new MailPartyView(null, "(unknown)", "unknown@" + MailAddresses.PLATFORM_DOMAIN, null);
    }
    String local = u.getMailLocalPart();
    if (local == null || local.isBlank()) {
      return new MailPartyView(u.getId(), u.getName(), u.getEmail(), u.getRole());
    }
    String domain =
        PLATFORM.contains(u.getRole()) || u.getCompanyId() == null
            ? MailAddresses.PLATFORM_DOMAIN
            : domains.getOrDefault(u.getCompanyId(), MailAddresses.PLATFORM_DOMAIN);
    return new MailPartyView(u.getId(), u.getName(), local + "@" + domain, u.getRole());
  }

  private Map<String, User> usersById(Iterable<String> ids) {
    Map<String, User> map = new HashMap<>();
    for (User u : users.findAllById(ids)) {
      map.put(u.getId(), u);
    }
    return map;
  }
}
