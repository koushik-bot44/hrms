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
import com.ihrms.mail.dto.MailDtos.InboxMessageView;
import com.ihrms.mail.dto.MailDtos.InboxPage;
import com.ihrms.mail.dto.MailDtos.MailPartyView;
import com.ihrms.mail.dto.MailDtos.MessageView;
import com.ihrms.mail.dto.MailDtos.SendMessageRequest;
import com.ihrms.mail.dto.MailDtos.SendMessageResult;
import com.ihrms.mail.dto.MailDtos.SentMessageView;
import com.ihrms.mail.dto.MailDtos.SentPage;
import com.ihrms.mail.dto.MailDtos.UnreadCountView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
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
 * Internal mail (ARCHITECTURE.md §8) — messaging between IHRMS staff accounts. A message is DB rows,
 * never external email. Every send is gated by the single authorization graph
 * ({@link AuthorizationService#canSendMail}); nothing here reaches across companies. Addresses are
 * cosmetic display handles ({@code localpart@domain}) resolved from the account's role/company — they
 * are never parsed to decide routing.
 */
@Service
public class InternalMailService {

  /** Company staff who share a mailbox namespace with their COMPANY_ADMIN. */
  private static final EnumSet<UserRole> COMPANY_STAFF =
      EnumSet.of(UserRole.HR, UserRole.MANAGER, UserRole.ACCOUNTANT);

  /** Accounts on the platform domain ({@code @ihrms}) rather than a company domain. */
  private static final EnumSet<UserRole> PLATFORM =
      EnumSet.of(UserRole.SUPER_ADMIN, UserRole.ACCOUNTS_ADMIN);

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

  // --- Send ---------------------------------------------------------------------

  /** Create a message to one recipient. Rejects (403) anything the send graph forbids. Audited. */
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

    Message message = new Message();
    message.setSenderUserId(me.getId());
    message.setSubject(input.subject().strip());
    message.setBody(input.body());
    messages.save(message);

    MessageRecipient row = new MessageRecipient();
    row.setMessageId(message.getId());
    row.setRecipientUserId(to.getId());
    recipients.save(row);

    // Attribute the event to the sender's company (portal-level for SUPER_ADMIN); mail can cross the
    // portal↔company boundary but never company↔company.
    audit.record(
        new AuditActor("USER", me.getId(), me.getCompanyId()),
        "MAIL_SENT",
        "Message",
        message.getId(),
        Map.of("toUserId", to.getId(), "subject", message.getSubject()),
        ip);

    return new SendMessageResult(message.getId());
  }

  // --- Inbox / Sent -------------------------------------------------------------

  /** Messages addressed to the caller, newest first, each with this recipient's read flag. */
  @Transactional(readOnly = true)
  public InboxPage inbox(IhrmsPrincipal.User actor, Pageable pageable) {
    Page<MessageRecipient> page = recipients.findInbox(actor.userId(), pageable);
    List<MessageRecipient> rows = page.getContent();

    Map<String, Message> byId = messagesById(rows.stream().map(MessageRecipient::getMessageId).toList());
    Map<String, User> senders =
        usersById(byId.values().stream().map(Message::getSenderUserId).toList());
    Map<String, String> domains = domainsFor(senders.values());

    List<InboxMessageView> content = new ArrayList<>();
    for (MessageRecipient r : rows) {
      Message m = byId.get(r.getMessageId());
      if (m == null) {
        continue; // sender's message was deleted (cascade) — skip the orphan defensively
      }
      User sender = senders.get(m.getSenderUserId());
      content.add(
          new InboxMessageView(
              m.getId(),
              m.getSubject(),
              m.getCreatedAt().toString(),
              party(sender, domains),
              r.getReadAt() != null));
    }
    return new InboxPage(
        content, page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
  }

  /** Messages the caller sent, newest first, each with its recipient(s). */
  @Transactional(readOnly = true)
  public SentPage sent(IhrmsPrincipal.User actor, Pageable pageable) {
    Page<Message> page = messages.findBySenderUserIdOrderByCreatedAtDesc(actor.userId(), pageable);
    List<Message> rows = page.getContent();

    Map<String, List<MessageRecipient>> byMessage = new HashMap<>();
    Set<String> recipientIds = new HashSet<>();
    for (Message m : rows) {
      List<MessageRecipient> rs = recipients.findByMessageId(m.getId());
      byMessage.put(m.getId(), rs);
      rs.forEach(r -> recipientIds.add(r.getRecipientUserId()));
    }
    Map<String, User> recipientUsers = usersById(recipientIds);
    Map<String, String> domains = domainsFor(recipientUsers.values());

    List<SentMessageView> content = new ArrayList<>();
    for (Message m : rows) {
      List<MailPartyView> to =
          byMessage.getOrDefault(m.getId(), List.of()).stream()
              .map(r -> recipientUsers.get(r.getRecipientUserId()))
              .filter(u -> u != null)
              .map(u -> party(u, domains))
              .collect(Collectors.toList());
      content.add(new SentMessageView(m.getId(), m.getSubject(), m.getCreatedAt().toString(), to));
    }
    return new SentPage(
        content, page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
  }

  // --- View one ------------------------------------------------------------------

  /**
   * Read one message. Only the sender or a recipient may open it (else 403). Opening it as the
   * recipient marks it read and writes a {@code MAIL_VIEWED} audit event.
   */
  @Transactional
  public MessageView view(IhrmsPrincipal.User actor, String id, String ip) {
    Message m =
        messages
            .findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Message not found"));

    boolean isSender = m.getSenderUserId().equals(actor.userId());
    MessageRecipient mine =
        recipients.findByMessageIdAndRecipientUserId(id, actor.userId()).orElse(null);
    if (!isSender && mine == null) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot view this message");
    }

    boolean read = mine == null || mine.getReadAt() != null;
    if (mine != null && mine.getReadAt() == null) {
      mine.setReadAt(Instant.now());
      recipients.save(mine);
      read = true;
    }

    List<MessageRecipient> allRecipients = recipients.findByMessageId(id);
    Set<String> partyIds = new HashSet<>();
    partyIds.add(m.getSenderUserId());
    allRecipients.forEach(r -> partyIds.add(r.getRecipientUserId()));
    Map<String, User> partyUsers = usersById(partyIds);
    Map<String, String> domains = domainsFor(partyUsers.values());

    User sender = partyUsers.get(m.getSenderUserId());
    List<MailPartyView> to =
        allRecipients.stream()
            .map(r -> partyUsers.get(r.getRecipientUserId()))
            .filter(u -> u != null)
            .map(u -> party(u, domains))
            .collect(Collectors.toList());

    audit.record(
        new AuditActor("USER", actor.userId(), actor.companyId()),
        "MAIL_VIEWED",
        "Message",
        m.getId(),
        Map.of("role", isSender ? "sender" : "recipient"),
        ip);

    return new MessageView(
        m.getId(), m.getSubject(), m.getBody(), m.getCreatedAt().toString(), party(sender, domains), to, read);
  }

  /** Count of unread messages in the caller's inbox (drives the nav badge). */
  @Transactional(readOnly = true)
  public UnreadCountView unreadCount(IhrmsPrincipal.User actor) {
    return new UnreadCountView(recipients.countByRecipientUserIdAndReadAtIsNull(actor.userId()));
  }

  // --- Helpers -------------------------------------------------------------------

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
      // Legacy account never assigned a local part — fall back to whatever the email already is.
      return new MailPartyView(u.getId(), u.getName(), u.getEmail(), u.getRole());
    }
    String domain =
        PLATFORM.contains(u.getRole()) || u.getCompanyId() == null
            ? MailAddresses.PLATFORM_DOMAIN
            : domains.getOrDefault(u.getCompanyId(), MailAddresses.PLATFORM_DOMAIN);
    return new MailPartyView(u.getId(), u.getName(), local + "@" + domain, u.getRole());
  }

  private Map<String, Message> messagesById(Iterable<String> ids) {
    Map<String, Message> map = new HashMap<>();
    for (Message m : messages.findAllById(ids)) {
      map.put(m.getId(), m);
    }
    return map;
  }

  private Map<String, User> usersById(Iterable<String> ids) {
    Map<String, User> map = new HashMap<>();
    for (User u : users.findAllById(ids)) {
      map.put(u.getId(), u);
    }
    return map;
  }
}
