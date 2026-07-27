package com.ihrms.mail;

import com.ihrms.audit.AuditActor;
import com.ihrms.audit.AuditService;
import com.ihrms.auth.AuthorizationService;
import com.ihrms.auth.AuthorizationService.MailParticipant;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.domain.enums.RecipientType;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.LabelThread;
import com.ihrms.domain.model.MailDraft;
import com.ihrms.domain.model.MailLabel;
import com.ihrms.domain.model.Message;
import com.ihrms.domain.model.MessageAttachment;
import com.ihrms.domain.model.MessageRecipient;
import com.ihrms.domain.model.ThreadArchive;
import com.ihrms.domain.model.ThreadStar;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.LabelThreadRepository;
import com.ihrms.domain.repository.MailDraftRepository;
import com.ihrms.domain.repository.MailLabelRepository;
import com.ihrms.domain.repository.MessageAttachmentRepository;
import com.ihrms.domain.repository.MessageRecipientRepository;
import com.ihrms.domain.repository.MessageRepository;
import com.ihrms.domain.repository.ThreadArchiveRepository;
import com.ihrms.domain.repository.ThreadStarRepository;
import com.ihrms.domain.repository.UserRepository;
import com.ihrms.domain.support.Cuids;
import com.ihrms.mail.dto.MailDtos.AttachmentDownload;
import com.ihrms.mail.dto.MailDtos.AttachmentUpload;
import com.ihrms.mail.dto.MailDtos.AttachmentUploadRequest;
import com.ihrms.mail.dto.MailDtos.AttachmentView;
import com.ihrms.mail.dto.MailDtos.DraftListItemView;
import com.ihrms.mail.dto.MailDtos.DraftPage;
import com.ihrms.mail.dto.MailDtos.DraftView;
import com.ihrms.mail.dto.MailDtos.LabelNameRequest;
import com.ihrms.mail.dto.MailDtos.LabelRef;
import com.ihrms.mail.dto.MailDtos.LabelView;
import com.ihrms.mail.dto.MailDtos.MailPartyView;
import com.ihrms.mail.dto.MailDtos.ReplyRequest;
import com.ihrms.mail.dto.MailDtos.SaveDraftRequest;
import com.ihrms.mail.dto.MailDtos.SendMessageRequest;
import com.ihrms.mail.dto.MailDtos.SendMessageResult;
import com.ihrms.mail.dto.MailDtos.ThreadDetailView;
import com.ihrms.mail.dto.MailDtos.ThreadListItemView;
import com.ihrms.mail.dto.MailDtos.ThreadMessageView;
import com.ihrms.mail.dto.MailDtos.ThreadPage;
import com.ihrms.mail.dto.MailDtos.UnreadCountView;
import com.ihrms.storage.StorageService;
import com.ihrms.support.Hashing;
import com.ihrms.web.ValidationException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Internal mail (ARCHITECTURE.md §8) — messaging between IHRMS accounts, organised into <b>threads</b>.
 * A participant (sender / recipient / uploader) is a staff {@link User} OR a credentialed
 * {@link Employee}: rows carry a user-id OR an employee-id, and since ids are globally-unique cuids the
 * "my mail" queries match either column against the same id. Every send AND every reply is gated by the
 * single authorization graph ({@link AuthorizationService#canSendMail}); nothing reaches across
 * companies. Delete is a per-user soft-hide (the row is never destroyed).
 */
@Service
public class InternalMailService {

  /** Accounts on the platform domain ({@code @ihrms}) rather than a company domain. */
  private static final EnumSet<UserRole> PLATFORM =
      EnumSet.of(UserRole.SUPER_ADMIN, UserRole.ACCOUNTS_ADMIN);

  private static final int SNIPPET = 140;

  private static final Logger log = LoggerFactory.getLogger(InternalMailService.class);

  private final UserRepository users;
  private final EmployeeRepository employees;
  private final CompanyRepository companies;
  private final MessageRepository messages;
  private final MessageRecipientRepository recipients;
  private final MessageAttachmentRepository attachments;
  private final ThreadStarRepository threadStars;
  private final ThreadArchiveRepository threadArchives;
  private final MailDraftRepository drafts;
  private final MailLabelRepository labels;
  private final LabelThreadRepository labelThreads;
  private final AuthorizationService authz;
  private final AuditService audit;
  private final StorageService storage;
  private final MailAttachments attachmentRules;
  private final Validator validator;

  public InternalMailService(
      UserRepository users,
      EmployeeRepository employees,
      CompanyRepository companies,
      MessageRepository messages,
      MessageRecipientRepository recipients,
      MessageAttachmentRepository attachments,
      ThreadStarRepository threadStars,
      ThreadArchiveRepository threadArchives,
      MailDraftRepository drafts,
      MailLabelRepository labels,
      LabelThreadRepository labelThreads,
      AuthorizationService authz,
      AuditService audit,
      StorageService storage,
      MailAttachments attachmentRules,
      Validator validator) {
    this.users = users;
    this.employees = employees;
    this.companies = companies;
    this.messages = messages;
    this.recipients = recipients;
    this.attachments = attachments;
    this.threadStars = threadStars;
    this.threadArchives = threadArchives;
    this.drafts = drafts;
    this.labels = labels;
    this.labelThreads = labelThreads;
    this.authz = authz;
    this.audit = audit;
    this.storage = storage;
    this.attachmentRules = attachmentRules;
    this.validator = validator;
  }

  // --- Contacts -----------------------------------------------------------------

  /**
   * The accounts the caller may message — the send graph resolved to a concrete list (§8). Gathers a
   * batched candidate SUPERSET (the caller's company staff + credentialed employees + the platform users,
   * or just the platform users when the caller is a platform admin) and filters each through the ONE
   * central {@link AuthorizationService#canSendMail} — so the compose list can never offer a disallowed
   * recipient and always mirrors the graph exactly. The caller's team keys are resolved ONCE (no N+1).
   */
  @Transactional(readOnly = true)
  public List<MailPartyView> contacts(IhrmsPrincipal actor) {
    MailParticipant me = resolveActor(actor);
    Set<String> myKeys = authz.mailTeamKeys(me);

    Set<User> userCandidates = new LinkedHashSet<>();
    List<Employee> employeeCandidates = new ArrayList<>();
    if (me.companyId() != null) {
      // Company-scoped: the company's staff + credentialed employees, plus the platform roles (only a
      // Company Admin actually reaches the Super Admin — the graph filter drops it for everyone else).
      userCandidates.addAll(users.findByCompanyId(me.companyId()));
      userCandidates.addAll(users.findByRoleIn(PLATFORM));
      employeeCandidates.addAll(employees.findByCompanyIdAndMailAddressIsNotNull(me.companyId()));
    } else {
      // Platform actor (Super / Accounts Admin): only company admins + the platform roles.
      userCandidates.addAll(
          users.findByRoleIn(
              List.of(UserRole.SUPER_ADMIN, UserRole.ACCOUNTS_ADMIN, UserRole.COMPANY_ADMIN)));
    }

    List<User> allowedUsers = new ArrayList<>();
    for (User u : userCandidates) {
      MailParticipant p = MailParticipant.user(u);
      if (authz.canSendMail(me, myKeys, p, authz.mailTeamKeys(p))) {
        allowedUsers.add(u);
      }
    }
    Map<String, String> domains = domainsFor(allowedUsers);
    List<MailPartyView> out = new ArrayList<>();
    allowedUsers.forEach(u -> out.add(party(u, domains)));

    for (Employee e : employeeCandidates) {
      MailParticipant p = MailParticipant.employee(e);
      if (authz.canSendMail(me, myKeys, p, authz.mailTeamKeys(p))) {
        out.add(employeeParty(e));
      }
    }
    out.sort(Comparator.comparing(MailPartyView::address));
    return out;
  }

  // --- Send (new thread) + Reply (join a thread) --------------------------------

  /**
   * Start a NEW thread with one or more recipients (TO + CC + BCC). EVERY recipient is re-checked through
   * the send graph — if ANY is disallowed the WHOLE send is rejected (403), never silently dropped. BCC
   * recipients are delivered but hidden from other recipients (enforced when the thread is rendered).
   * Audited.
   */
  @Transactional
  public SendMessageResult send(IhrmsPrincipal actor, SendMessageRequest input, String ip) {
    MailParticipant me = resolveActor(actor);
    // Typed recipient set, de-duped across fields (first/highest-visibility placement wins).
    LinkedHashMap<String, RecipientType> typed =
        typedRecipients(input.toUserIds(), input.ccUserIds(), input.bccUserIds());
    if (typed.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one recipient is required");
    }
    Map<String, MailParticipant> resolved = resolveAndAuthorize(me, typed.keySet());

    String threadId = Cuids.newId(); // a fresh conversation
    Message message = new Message();
    message.setThreadId(threadId);
    setSender(message, me);
    message.setSubject(input.subject().strip());
    message.setBody(input.body());
    messages.save(message);
    typed.forEach((id, type) -> saveRecipient(message.getId(), resolved.get(id), type));
    clearRecipientArchives(threadId, resolved.values()); // new inbound activity resurfaces to Inbox
    int bound = bindAttachments(me, message.getId(), input.attachmentIds());

    auditSent(me, message, typed.size(), bound, ip);
    return new SendMessageResult(message.getId(), threadId);
  }

  /**
   * Reply within a thread — to the ORIGINAL SENDER only. The recipient is DERIVED from the thread (never
   * supplied), and a reply IS a send, so {@link AuthorizationService#canSendMail} is re-checked (403 if
   * the graph now forbids it). Audited {@code MAIL_SENT}.
   */
  @Transactional
  public SendMessageResult reply(IhrmsPrincipal actor, String threadId, ReplyRequest input, String ip) {
    return replyInternal(actor, threadId, input, false, ip);
  }

  /**
   * Reply ALL within a thread — to the original sender + all TO + all CC, EXCLUDING the actor and NEVER
   * any BCC (BCC is not propagated, and a BCC recipient's status is never revealed). Each derived recipient
   * is re-checked through the graph. Audited {@code MAIL_SENT}.
   */
  @Transactional
  public SendMessageResult replyAll(IhrmsPrincipal actor, String threadId, ReplyRequest input, String ip) {
    return replyInternal(actor, threadId, input, true, ip);
  }

  private SendMessageResult replyInternal(
      IhrmsPrincipal actor, String threadId, ReplyRequest input, boolean all, String ip) {
    MailParticipant me = resolveActor(actor);
    List<Message> msgs = messages.findByThreadIdOrderByCreatedAtAsc(threadId);
    if (msgs.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Thread not found");
    }
    Map<String, List<MessageRecipient>> recByMsg = recipientsByMessage(msgs);
    if (!isParticipant(me.id(), msgs, recByMsg)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You are not part of this conversation");
    }

    // Derive the recipient set from the thread — never free-typed.
    Set<String> recipientIds =
        all ? replyAllRecipientIds(me.id(), msgs, recByMsg) : replyToSender(me.id(), msgs, recByMsg);
    if (recipientIds.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "This conversation has no one to reply to");
    }
    // The graph still governs replies — re-check EVERY recipient (roles/companies can change mid-thread).
    Map<String, MailParticipant> resolved = resolveAndAuthorize(me, recipientIds);

    Message reply = new Message();
    reply.setThreadId(threadId);
    reply.setParentMessageId(msgs.get(msgs.size() - 1).getId());
    setSender(reply, me);
    reply.setSubject(msgs.get(0).getSubject()); // the thread keeps its subject
    reply.setBody(input.body());
    messages.save(reply);
    resolved.values().forEach(to -> saveRecipient(reply.getId(), to, RecipientType.TO)); // replies address TO
    clearRecipientArchives(threadId, resolved.values()); // a reply resurfaces the thread to each recipient's Inbox
    int bound = bindAttachments(me, reply.getId(), input.attachmentIds());

    auditSent(me, reply, resolved.size(), bound, ip);
    return new SendMessageResult(reply.getId(), threadId);
  }

  // --- Attachments (§8, Stage 4) ------------------------------------------------

  /**
   * Step 1 of the upload handshake: validate the file (type + extension + size, server-authoritative),
   * allocate a storage key, create an unbound DRAFT, and return a short-lived presigned PUT.
   */
  @Transactional
  public AttachmentUpload requestAttachmentUpload(
      IhrmsPrincipal actor, AttachmentUploadRequest input, String ip) {
    MailParticipant me = resolveActor(actor);
    String type = attachmentRules.validate(input.fileName(), input.contentType(), input.sizeBytes());

    String key = storage.buildMailAttachmentKey(me.id(), input.fileName());
    MessageAttachment draft = new MessageAttachment();
    if (isUser(me)) {
      draft.setUploaderUserId(me.id());
    } else {
      draft.setUploaderEmployeeId(me.id());
    }
    draft.setFileName(input.fileName());
    draft.setContentType(type);
    draft.setSizeBytes(input.sizeBytes());
    draft.setStorageKey(key);
    attachments.save(draft);

    String uploadUrl = storage.presignedPutUrl(key, type, MailAttachments.UPLOAD_TTL_SECONDS);
    audit.record(
        actorOf(me),
        "MAIL_ATTACHMENT_UPLOAD_REQUESTED",
        "MessageAttachment",
        draft.getId(),
        Map.of("fileName", input.fileName(), "contentType", type),
        ip);
    return new AttachmentUpload(
        draft.getId(), uploadUrl, "PUT", Map.of("Content-Type", type), MailAttachments.UPLOAD_TTL_SECONDS);
  }

  /**
   * Download an attachment: resolve it → its message → its thread and issue a presigned GET ONLY if the
   * caller is a participant in that thread (else 403 — even a Super Admin who is not a participant).
   * Access is thread-scoped, never company-scoped. Audited {@code MAIL_ATTACHMENT_DOWNLOADED}.
   */
  @Transactional
  public AttachmentDownload downloadAttachment(IhrmsPrincipal actor, String attachmentId, String ip) {
    MailParticipant me = resolveActor(actor);
    MessageAttachment att =
        attachments
            .findById(attachmentId)
            .filter(a -> a.getMessageId() != null) // unbound drafts are not downloadable
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Attachment not found"));
    Message message =
        messages
            .findById(att.getMessageId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Attachment not found"));

    List<Message> threadMsgs = messages.findByThreadIdOrderByCreatedAtAsc(message.getThreadId());
    if (!isParticipant(me.id(), threadMsgs, recipientsByMessage(threadMsgs))) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot download this attachment");
    }

    String url = storage.presignedGetUrl(att.getStorageKey(), MailAttachments.DOWNLOAD_TTL_SECONDS);
    audit.record(
        actorOf(me),
        "MAIL_ATTACHMENT_DOWNLOADED",
        "MessageAttachment",
        att.getId(),
        Map.of("threadId", message.getThreadId(), "messageId", message.getId(), "fileName", att.getFileName()),
        ip);
    return new AttachmentDownload(url, MailAttachments.DOWNLOAD_TTL_SECONDS);
  }

  /**
   * Bind the caller's uploaded drafts to a freshly-created message. Each draft must be theirs, still
   * unbound, and actually present in storage; its real size + sha256 are (re)computed and the limits +
   * type are re-enforced (never trusting the client). Returns how many were bound.
   */
  private int bindAttachments(MailParticipant me, String messageId, List<String> attachmentIds) {
    if (attachmentIds == null || attachmentIds.isEmpty()) {
      return 0;
    }
    List<String> ids = attachmentIds.stream().distinct().toList();
    if (ids.size() > MailAttachments.MAX_PER_MESSAGE) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "At most " + MailAttachments.MAX_PER_MESSAGE + " attachments per message");
    }
    List<MessageAttachment> toBind = new ArrayList<>();
    for (String id : ids) {
      MessageAttachment att =
          attachments
              .findById(id)
              .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown attachment"));
      if (!me.id().equals(att.uploaderAccountId()) || att.getMessageId() != null) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That attachment is not available");
      }
      byte[] bytes = storage.getObjectBytes(att.getStorageKey());
      if (bytes == null || bytes.length == 0) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An attachment upload did not complete");
      }
      // Re-validate against the ACTUAL stored bytes (size) + the declared type (never trust the client).
      attachmentRules.validate(att.getFileName(), att.getContentType(), bytes.length);
      att.setSizeBytes(bytes.length);
      att.setSha256(Hashing.sha256Hex(bytes));
      att.setMessageId(messageId);
      toBind.add(att);
    }
    attachments.saveAll(toBind);
    return toBind.size();
  }

  // --- Lists: Inbox / Sent / Search (all THREADS) -------------------------------

  /** Inbox as threads: conversations with a message addressed to the caller, newest activity first. */
  @Transactional(readOnly = true)
  public ThreadPage inbox(IhrmsPrincipal actor, Pageable pageable) {
    MailParticipant me = resolveActor(actor);
    Page<String> ids = messages.findInboxThreadIds(me.id(), pageable);
    return page(ids, buildThreadList(me.id(), ids.getContent()));
  }

  /** Sent as threads: conversations the caller has sent into. */
  @Transactional(readOnly = true)
  public ThreadPage sent(IhrmsPrincipal actor, Pageable pageable) {
    MailParticipant me = resolveActor(actor);
    Page<String> ids = messages.findSentThreadIds(me.id(), pageable);
    return page(ids, buildThreadList(me.id(), ids.getContent()));
  }

  /**
   * Search + FILTER the caller's OWN mail (§8). Text {@code q} (subject/body) ANDs with any of: {@code
   * from} (sender by address/name), {@code after}/{@code before} (thread latest-activity bounds, ISO
   * instants), {@code hasAttachment}/{@code unread}/{@code starred} (the viewer's own thread flags), and
   * {@code scope} (ALL/INBOX/SENT/STARRED/ARCHIVE, default ALL). All optional; empty criteria returns
   * nothing (as before). One participant-scoped query — never another mailbox.
   */
  @Transactional(readOnly = true)
  public ThreadPage search(
      IhrmsPrincipal actor,
      String q,
      String from,
      String after,
      String before,
      boolean hasAttachment,
      boolean unread,
      boolean starred,
      String scope,
      Pageable pageable) {
    MailParticipant me = resolveActor(actor);
    String term = like(q);
    String sender = like(from);
    Instant afterAt = parseInstant(after, "after");
    Instant beforeAt = parseInstant(before, "before");
    String view = normalizeScope(scope);

    boolean anyCriteria =
        term != null
            || sender != null
            || afterAt != null
            || beforeAt != null
            || hasAttachment
            || unread
            || starred
            || !"ALL".equals(view);
    if (!anyCriteria) {
      // No text and no filters — same as the prior search: nothing (don't dump the whole mailbox).
      return new ThreadPage(List.of(), 0, pageable.getPageSize(), 0, 0);
    }
    Page<String> ids =
        messages.searchThreadIds(
            me.id(), term, sender, afterAt, beforeAt, hasAttachment, unread, starred, view, pageable);
    return page(ids, buildThreadList(me.id(), ids.getContent()));
  }

  /** A lowercased {@code %term%} LIKE pattern, or null when blank (=> the clause is skipped). */
  private static String like(String s) {
    return s == null || s.isBlank() ? null : "%" + s.strip().toLowerCase() + "%";
  }

  /** Parse an optional ISO-8601 instant filter bound; a malformed value is a 400 (never a silent no-op). */
  private static Instant parseInstant(String s, String field) {
    if (s == null || s.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(s.strip());
    } catch (java.time.format.DateTimeParseException e) {
      throw new ValidationException(List.of(field + ": expected an ISO-8601 timestamp"));
    }
  }

  private static String normalizeScope(String scope) {
    if (scope == null || scope.isBlank()) {
      return "ALL";
    }
    String s = scope.strip().toUpperCase();
    return switch (s) {
      case "INBOX", "SENT", "STARRED", "ARCHIVE", "ALL" -> s;
      default -> "ALL";
    };
  }

  // --- Starred (per-user, thread-level, §8) -------------------------------------

  /** Starred as THREADS: the viewer's starred conversations — same list shape + soft-delete scoping as Inbox. */
  @Transactional(readOnly = true)
  public ThreadPage starred(IhrmsPrincipal actor, Pageable pageable) {
    MailParticipant me = resolveActor(actor);
    Page<String> ids = messages.findStarredThreadIds(me.id(), pageable);
    return page(ids, buildThreadList(me.id(), ids.getContent()));
  }

  /** Star a thread for the caller only (idempotent). Only a participant may star it. Audited. */
  @Transactional
  public void star(IhrmsPrincipal actor, String threadId, String ip) {
    MailParticipant me = requireThreadParticipant(actor, threadId);
    if (!threadStars.existsByThreadIdAndAccountId(threadId, me.id())) {
      threadStars.save(new ThreadStar(threadId, me.id()));
      audit.record(actorOf(me), "MAIL_STARRED", "Thread", threadId, Map.of(), ip);
    }
  }

  /** Unstar a thread for the caller only (idempotent). Audited only when a star is actually cleared. */
  @Transactional
  public void unstar(IhrmsPrincipal actor, String threadId, String ip) {
    MailParticipant me = requireThreadParticipant(actor, threadId);
    threadStars
        .findByThreadIdAndAccountId(threadId, me.id())
        .ifPresent(
            s -> {
              threadStars.delete(s);
              audit.record(actorOf(me), "MAIL_UNSTARRED", "Thread", threadId, Map.of(), ip);
            });
  }

  // --- Archived (per-user, thread-level, §8 — like Starred, but hides from Inbox) ------------------

  /** Archived as THREADS: the viewer's archived conversations — same list shape + soft-delete scoping as Inbox. */
  @Transactional(readOnly = true)
  public ThreadPage archived(IhrmsPrincipal actor, Pageable pageable) {
    MailParticipant me = resolveActor(actor);
    Page<String> ids = messages.findArchivedThreadIds(me.id(), pageable);
    return page(ids, buildThreadList(me.id(), ids.getContent()));
  }

  /**
   * Archive a thread for the caller only (idempotent) — hides it from THEIR Inbox (not deleted; still in
   * Archive/Sent/Search/Starred, and normal for others). Only a participant may archive it. Audited.
   */
  @Transactional
  public void archive(IhrmsPrincipal actor, String threadId, String ip) {
    MailParticipant me = requireThreadParticipant(actor, threadId);
    if (!threadArchives.existsByThreadIdAndAccountId(threadId, me.id())) {
      threadArchives.save(new ThreadArchive(threadId, me.id()));
      audit.record(actorOf(me), "MAIL_ARCHIVED", "Thread", threadId, Map.of(), ip);
    }
  }

  /** Unarchive a thread for the caller only (idempotent) — returns it to their Inbox. Audited on a real clear. */
  @Transactional
  public void unarchive(IhrmsPrincipal actor, String threadId, String ip) {
    MailParticipant me = requireThreadParticipant(actor, threadId);
    threadArchives
        .findByThreadIdAndAccountId(threadId, me.id())
        .ifPresent(
            a -> {
              threadArchives.delete(a);
              audit.record(actorOf(me), "MAIL_UNARCHIVED", "Thread", threadId, Map.of(), ip);
            });
  }

  /** Resolve the caller + confirm they participate in the thread (404 if it doesn't exist, 403 if not theirs). */
  private MailParticipant requireThreadParticipant(IhrmsPrincipal actor, String threadId) {
    MailParticipant me = resolveActor(actor);
    List<Message> msgs = messages.findByThreadIdOrderByCreatedAtAsc(threadId);
    if (msgs.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Thread not found");
    }
    if (!isParticipant(me.id(), msgs, recipientsByMessage(msgs))) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You are not part of this conversation");
    }
    return me;
  }

  // --- Open one thread ----------------------------------------------------------

  /**
   * Open a thread: its messages chronologically, plus the counterparty a reply would go to. Only a
   * participant may open it (else 403). Marks the caller's unread messages read; audited {@code MAIL_VIEWED}.
   */
  @Transactional
  public ThreadDetailView thread(IhrmsPrincipal actor, String threadId, String ip) {
    MailParticipant me = resolveActor(actor);
    List<Message> msgs = messages.findByThreadIdOrderByCreatedAtAsc(threadId);
    if (msgs.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Thread not found");
    }
    Map<String, List<MessageRecipient>> recByMsg = recipientsByMessage(msgs);
    Map<String, MessageRecipient> mine = myRows(me.id(), recByMsg);
    if (!isParticipant(me.id(), msgs, recByMsg)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot view this conversation");
    }

    List<Message> visible = msgs.stream().filter(m -> visibleToMe(me.id(), m, mine)).toList();
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

    // partyMap resolves ONLY the accounts this viewer may see (hidden BCCs never enter it) — a hard
    // boundary against leaking a BCC identity into any part of the response.
    Map<String, MailPartyView> partyMap =
        partiesById(visibleParticipantIds(me.id(), msgs, recByMsg, null));
    Map<String, List<MessageAttachment>> attByMsg = attachmentsByMessage(visible);

    List<ThreadMessageView> messageViews =
        visible.stream()
            .map(
                m -> {
                  List<MessageRecipient> rs = recByMsg.getOrDefault(m.getId(), List.of());
                  List<MailPartyView> bcc =
                      rs.stream()
                          .filter(r -> r.getRecipientType() == RecipientType.BCC && bccVisible(me.id(), m, r))
                          .map(r -> partyMap.get(r.recipientAccountId()))
                          .filter(Objects::nonNull)
                          .collect(Collectors.toList());
                  return new ThreadMessageView(
                      m.getId(),
                      partyMap.get(m.senderAccountId()),
                      partiesOfType(rs, RecipientType.TO, partyMap),
                      partiesOfType(rs, RecipientType.CC, partyMap),
                      bcc,
                      m.getBody(),
                      m.getCreatedAt().toString(),
                      m.senderAccountId().equals(me.id()),
                      attachmentViews(attByMsg.get(m.getId())));
                })
            .toList();
    List<MailPartyView> participants = otherParties(me.id(), msgs, recByMsg, partyMap);
    String otherId = replyToSender(me.id(), msgs, recByMsg).stream().findFirst().orElse(null);
    MailPartyView counterparty = otherId == null ? null : partyMap.get(otherId);

    audit.record(
        actorOf(me), "MAIL_VIEWED", "Thread", threadId, Map.of("messageCount", visible.size()), ip);

    boolean isStarred = threadStars.existsByThreadIdAndAccountId(threadId, me.id());
    boolean isArchived = threadArchives.existsByThreadIdAndAccountId(threadId, me.id());
    List<LabelRef> labelRefs = labelsByThread(me.id(), List.of(threadId)).getOrDefault(threadId, List.of());
    return new ThreadDetailView(
        threadId,
        msgs.get(0).getSubject(),
        participants,
        counterparty,
        messageViews,
        isStarred,
        isArchived,
        labelRefs);
  }

  // --- Read state ---------------------------------------------------------------

  /** Mark a whole thread read for the caller; returns the refreshed unread-thread count. */
  @Transactional
  public UnreadCountView markRead(IhrmsPrincipal actor, String threadId) {
    setReadState(resolveActor(actor).id(), threadId, true);
    return unreadCount(actor);
  }

  /** Mark a whole thread unread for the caller; returns the refreshed unread-thread count. */
  @Transactional
  public UnreadCountView markUnread(IhrmsPrincipal actor, String threadId) {
    setReadState(resolveActor(actor).id(), threadId, false);
    return unreadCount(actor);
  }

  /** Thread-level unread badge (§8): how many threads have an unopened message for the caller. */
  @Transactional(readOnly = true)
  public UnreadCountView unreadCount(IhrmsPrincipal actor) {
    return new UnreadCountView(messages.countUnreadThreads(resolveActor(actor).id()));
  }

  // --- Delete (per-user soft-hide) ----------------------------------------------

  /**
   * Delete a thread for the caller ONLY (§8): soft-hide their own copies — the message rows are never
   * destroyed and the counterparty still sees the conversation. Idempotent; audited {@code MAIL_DELETED}.
   */
  @Transactional
  public void delete(IhrmsPrincipal actor, String threadId, String ip) {
    MailParticipant me = resolveActor(actor);
    List<Message> msgs = messages.findByThreadIdOrderByCreatedAtAsc(threadId);
    if (msgs.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Thread not found");
    }
    Map<String, List<MessageRecipient>> recByMsg = recipientsByMessage(msgs);
    Map<String, MessageRecipient> mine = myRows(me.id(), recByMsg);
    if (!isParticipant(me.id(), msgs, recByMsg)) {
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
            .filter(m -> me.id().equals(m.senderAccountId()) && m.getSenderDeletedAt() == null)
            .toList();
    hideSent.forEach(m -> m.setSenderDeletedAt(now));
    if (!hideSent.isEmpty()) {
      messages.saveAll(hideSent);
    }

    audit.record(
        actorOf(me),
        "MAIL_DELETED",
        "Thread",
        threadId,
        Map.of("hiddenMessages", hideReceived.size() + hideSent.size()),
        ip);
  }

  // --- Drafts (author-private, unsent; §8) --------------------------------------

  /** The author's drafts, most-recently-edited first (author-only — never anyone else's). */
  @Transactional(readOnly = true)
  public DraftPage listDrafts(IhrmsPrincipal actor, Pageable pageable) {
    MailParticipant me = resolveActor(actor);
    Page<MailDraft> page = drafts.findByAuthorAccountIdOrderByUpdatedAtDesc(me.id(), pageable);
    List<MailDraft> ds = page.getContent();

    // Batch-resolve recipient parties + attachment ids across the whole page (no N+1).
    Set<String> recipientIds = new LinkedHashSet<>();
    Set<String> attIds = new LinkedHashSet<>();
    for (MailDraft d : ds) {
      recipientIds.addAll(draftRecipientOrder(d));
      if (d.getAttachmentIds() != null) {
        attIds.addAll(d.getAttachmentIds());
      }
    }
    Map<String, MailPartyView> parties = partiesById(recipientIds);
    Set<String> liveAtt =
        attIds.isEmpty()
            ? Set.of()
            : attachments.findAllById(attIds).stream()
                .filter(a -> a.getMessageId() == null && me.id().equals(a.uploaderAccountId()))
                .map(MessageAttachment::getId)
                .collect(Collectors.toSet());

    List<DraftListItemView> rows =
        ds.stream()
            .map(
                d ->
                    new DraftListItemView(
                        d.getId(),
                        d.getSubject(),
                        snippet(d.getBody()),
                        draftRecipientOrder(d).stream()
                            .map(parties::get)
                            .filter(Objects::nonNull)
                            .collect(Collectors.toList()),
                        d.getAttachmentIds() != null
                            && d.getAttachmentIds().stream().anyMatch(liveAtt::contains),
                        d.getReplyToThreadId() != null,
                        d.getUpdatedAt().toString()))
            .collect(Collectors.toList());
    return new DraftPage(
        rows, page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
  }

  /** Open one of the author's drafts for editing — everything the composer needs to reopen it. */
  @Transactional(readOnly = true)
  public DraftView getDraft(IhrmsPrincipal actor, String id) {
    MailParticipant me = resolveActor(actor);
    return draftView(requireOwnDraft(me, id));
  }

  /** Create a NEW draft (permissive — no canSendMail, no required fields). Audited {@code DRAFT_SAVED}. */
  @Transactional
  public DraftView saveDraft(IhrmsPrincipal actor, SaveDraftRequest input, String ip) {
    MailParticipant me = resolveActor(actor);
    MailDraft d = new MailDraft(me.id());
    applyDraft(d, input);
    drafts.saveAndFlush(d); // flush so @CreationTimestamp/@UpdateTimestamp are populated for the response
    audit.record(actorOf(me), "DRAFT_SAVED", "MailDraft", d.getId(), Map.of(), ip);
    return draftView(d);
  }

  /** Replace an existing draft's contents (still permissive). Author-only. Audited {@code DRAFT_SAVED}. */
  @Transactional
  public DraftView updateDraft(IhrmsPrincipal actor, String id, SaveDraftRequest input, String ip) {
    MailParticipant me = resolveActor(actor);
    MailDraft d = requireOwnDraft(me, id);
    applyDraft(d, input);
    drafts.saveAndFlush(d); // flush so the refreshed @UpdateTimestamp is in the response
    audit.record(actorOf(me), "DRAFT_SAVED", "MailDraft", id, Map.of(), ip);
    return draftView(d);
  }

  /** Discard a draft: delete it + best-effort remove its still-unbound attachment objects. Author-only. */
  @Transactional
  public void discardDraft(IhrmsPrincipal actor, String id, String ip) {
    MailParticipant me = resolveActor(actor);
    MailDraft d = requireOwnDraft(me, id);
    cleanupDraftAttachments(d, me.id());
    drafts.delete(d);
    audit.record(actorOf(me), "DRAFT_DISCARDED", "MailDraft", id, Map.of(), ip);
  }

  /**
   * Send a draft — runs the REAL send path. Reconstructs the compose (or reply) request, applies the SAME
   * validation as a normal compose (≥1 recipient, non-empty subject/body, ≤5 attachments) and — via
   * {@link #send}/{@link #reply}/{@link #replyAll} — {@code canSendMail} per recipient + delivery + threading
   * + attachment binding, then DELETES the draft. All one transaction: if validation or the send graph
   * rejects it, nothing is delivered and the draft REMAINS (the error surfaces). Returns the sent message so
   * the controller can fire the after-commit push, exactly like a normal compose.
   */
  @Transactional
  public SendMessageResult sendDraft(IhrmsPrincipal actor, String id, String ip) {
    MailParticipant me = resolveActor(actor);
    MailDraft d = requireOwnDraft(me, id);

    SendMessageResult result;
    if (d.getReplyToThreadId() != null) {
      ReplyRequest req = new ReplyRequest(d.getBody(), d.getAttachmentIds());
      validateOrThrow(req);
      result =
          d.isReplyAll()
              ? replyAll(actor, d.getReplyToThreadId(), req, ip)
              : reply(actor, d.getReplyToThreadId(), req, ip);
    } else {
      SendMessageRequest req =
          new SendMessageRequest(
              d.getToIds(), d.getCcIds(), d.getBccIds(), d.getSubject(), d.getBody(), d.getAttachmentIds());
      validateOrThrow(req);
      result = send(actor, req, ip);
    }
    drafts.delete(d); // the draft became the sent message
    return result;
  }

  private MailDraft requireOwnDraft(MailParticipant me, String id) {
    return drafts
        .findByIdAndAuthorAccountId(id, me.id())
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Draft not found"));
  }

  private void applyDraft(MailDraft d, SaveDraftRequest in) {
    d.setToIds(in.toUserIds());
    d.setCcIds(in.ccUserIds());
    d.setBccIds(in.bccUserIds());
    d.setSubject(in.subject());
    d.setBody(in.body());
    d.setAttachmentIds(in.attachmentIds());
    d.setReplyToThreadId(in.replyToThreadId());
    d.setReplyAll(in.replyAll());
  }

  /** The draft's recipients in TO→CC→BCC order, de-duped, skipping blanks (for display resolution). */
  private List<String> draftRecipientOrder(MailDraft d) {
    LinkedHashSet<String> ids = new LinkedHashSet<>();
    for (List<String> list : List.of(orEmpty(d.getToIds()), orEmpty(d.getCcIds()), orEmpty(d.getBccIds()))) {
      for (String s : list) {
        if (s != null && !s.isBlank()) {
          ids.add(s);
        }
      }
    }
    return new ArrayList<>(ids);
  }

  private DraftView draftView(MailDraft d) {
    Map<String, MailPartyView> parties = partiesById(draftRecipientOrder(d));
    return new DraftView(
        d.getId(),
        d.getSubject(),
        d.getBody(),
        partiesInOrder(d.getToIds(), parties),
        partiesInOrder(d.getCcIds(), parties),
        partiesInOrder(d.getBccIds(), parties),
        attachmentViews(draftAttachments(d)),
        d.getReplyToThreadId(),
        d.isReplyAll(),
        d.getUpdatedAt().toString());
  }

  private List<MailPartyView> partiesInOrder(List<String> ids, Map<String, MailPartyView> resolved) {
    if (ids == null) {
      return List.of();
    }
    return ids.stream().map(resolved::get).filter(Objects::nonNull).collect(Collectors.toList());
  }

  /** The draft's own, still-unbound attachment rows (author-scoped — never another account's uploads). */
  private List<MessageAttachment> draftAttachments(MailDraft d) {
    if (d.getAttachmentIds() == null || d.getAttachmentIds().isEmpty()) {
      return List.of();
    }
    return attachments.findAllById(d.getAttachmentIds()).stream()
        .filter(a -> a.getMessageId() == null && d.getAuthorAccountId().equals(a.uploaderAccountId()))
        .collect(Collectors.toList());
  }

  private void cleanupDraftAttachments(MailDraft d, String meId) {
    for (MessageAttachment a : draftAttachments(d)) {
      try {
        storage.delete(a.getStorageKey());
      } catch (RuntimeException e) {
        log.warn("Draft attachment object delete failed (best-effort): {}", e.getMessage());
      }
      try {
        attachments.delete(a);
      } catch (RuntimeException e) {
        log.warn("Draft attachment row delete failed (best-effort): {}", e.getMessage());
      }
    }
  }

  /** Run bean validation on the reconstructed compose/reply request — same 400s as a normal @Valid send. */
  private void validateOrThrow(Object request) {
    Set<ConstraintViolation<Object>> violations = validator.validate(request);
    if (!violations.isEmpty()) {
      List<String> messages =
          violations.stream()
              .map(v -> v.getPropertyPath() + ": " + v.getMessage())
              .sorted()
              .collect(Collectors.toList());
      throw new ValidationException(messages);
    }
  }

  private static List<String> orEmpty(List<String> list) {
    return list == null ? List.of() : list;
  }

  // --- Labels (author-private tags; §8) -----------------------------------------

  /** The caller's labels (alphabetical), each with its current thread-tag count. */
  @Transactional(readOnly = true)
  public List<LabelView> listLabels(IhrmsPrincipal actor) {
    MailParticipant me = resolveActor(actor);
    return labels.findByAuthorAccountIdOrderByNameAsc(me.id()).stream()
        .map(l -> new LabelView(l.getId(), l.getName(), labelThreads.countByLabelId(l.getId())))
        .collect(Collectors.toList());
  }

  /** Create a label for the caller. Names are unique per author, case-insensitively (409 on a dup). Audited. */
  @Transactional
  public LabelView createLabel(IhrmsPrincipal actor, LabelNameRequest input, String ip) {
    MailParticipant me = resolveActor(actor);
    String name = input.name().strip();
    if (labels.existsByAuthorAccountIdAndNameIgnoreCase(me.id(), name)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "A label with that name already exists");
    }
    MailLabel label = labels.save(new MailLabel(me.id(), name));
    audit.record(actorOf(me), "LABEL_CREATED", "MailLabel", label.getId(), Map.of("name", name), ip);
    return new LabelView(label.getId(), label.getName(), 0);
  }

  /** Rename one of the caller's labels (still unique per author). Author-only. Audited. */
  @Transactional
  public LabelView renameLabel(IhrmsPrincipal actor, String id, LabelNameRequest input, String ip) {
    MailParticipant me = resolveActor(actor);
    MailLabel label = requireOwnLabel(me, id);
    String name = input.name().strip();
    if (labels.existsByAuthorAccountIdAndNameIgnoreCaseAndIdNot(me.id(), name, id)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "A label with that name already exists");
    }
    label.setName(name);
    labels.save(label);
    audit.record(actorOf(me), "LABEL_RENAMED", "MailLabel", id, Map.of("name", name), ip);
    return new LabelView(id, name, labelThreads.countByLabelId(id));
  }

  /** Delete one of the caller's labels + all its assignments (threads untouched). Author-only. Audited. */
  @Transactional
  public void deleteLabel(IhrmsPrincipal actor, String id, String ip) {
    MailParticipant me = resolveActor(actor);
    MailLabel label = requireOwnLabel(me, id);
    int tagged = (int) labelThreads.countByLabelId(id);
    labels.delete(label); // FK ON DELETE CASCADE removes the label_threads assignments
    audit.record(actorOf(me), "LABEL_DELETED", "MailLabel", id, Map.of("untagged", tagged), ip);
  }

  /** Tag a thread with one of the caller's labels (idempotent). Participant-only on the thread. Audited. */
  @Transactional
  public void applyLabel(IhrmsPrincipal actor, String threadId, String labelId, String ip) {
    MailParticipant me = requireThreadParticipant(actor, threadId);
    requireOwnLabel(me, labelId);
    if (!labelThreads.existsByLabelIdAndThreadId(labelId, threadId)) {
      labelThreads.save(new LabelThread(labelId, threadId));
      audit.record(actorOf(me), "THREAD_LABELED", "Thread", threadId, Map.of("labelId", labelId), ip);
    }
  }

  /** Remove a label from a thread (idempotent). Author-only label + participant-only thread. Audited. */
  @Transactional
  public void removeLabel(IhrmsPrincipal actor, String threadId, String labelId, String ip) {
    MailParticipant me = requireThreadParticipant(actor, threadId);
    requireOwnLabel(me, labelId);
    if (labelThreads.existsByLabelIdAndThreadId(labelId, threadId)) {
      labelThreads.deleteByLabelIdAndThreadId(labelId, threadId);
      audit.record(actorOf(me), "THREAD_UNLABELED", "Thread", threadId, Map.of("labelId", labelId), ip);
    }
  }

  /**
   * A LABEL VIEW: the caller's threads tagged with their label — same list shape + soft-delete scoping as
   * Inbox, but INDEPENDENT of archive (a label is a tag overlay). Author-only (404 if the label isn't theirs).
   */
  @Transactional(readOnly = true)
  public ThreadPage labelThreads(IhrmsPrincipal actor, String labelId, Pageable pageable) {
    MailParticipant me = resolveActor(actor);
    requireOwnLabel(me, labelId);
    Page<String> ids = messages.findLabelThreadIds(me.id(), labelId, pageable);
    return page(ids, buildThreadList(me.id(), ids.getContent()));
  }

  private MailLabel requireOwnLabel(MailParticipant me, String labelId) {
    return labels
        .findByIdAndAuthorAccountId(labelId, me.id())
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Label not found"));
  }

  /** The viewer's OWN labels applied to each of a page of threads (chips), alphabetical. Batched — no N+1. */
  private Map<String, List<LabelRef>> labelsByThread(String meId, Collection<String> threadIds) {
    if (threadIds.isEmpty()) {
      return Map.of();
    }
    List<MailLabel> mine = labels.findByAuthorAccountIdOrderByNameAsc(meId);
    if (mine.isEmpty()) {
      return Map.of();
    }
    Map<String, MailLabel> byId = new HashMap<>();
    mine.forEach(l -> byId.put(l.getId(), l));
    Map<String, List<LabelRef>> out = new HashMap<>();
    for (LabelThread lt : labelThreads.findByLabelIdInAndThreadIdIn(byId.keySet(), threadIds)) {
      MailLabel l = byId.get(lt.getLabelId());
      if (l != null) {
        out.computeIfAbsent(lt.getThreadId(), k -> new ArrayList<>())
            .add(new LabelRef(l.getId(), l.getName()));
      }
    }
    out.values()
        .forEach(list -> list.sort(Comparator.comparing(LabelRef::name, String.CASE_INSENSITIVE_ORDER)));
    return out;
  }

  // --- Thread building ----------------------------------------------------------

  private ThreadPage page(Page<String> ids, List<ThreadListItemView> content) {
    return new ThreadPage(
        content, ids.getNumber(), ids.getSize(), ids.getTotalElements(), ids.getTotalPages());
  }

  /** Build the list rows for a page of threadIds, from the viewer's perspective (batched, no N+1). */
  private List<ThreadListItemView> buildThreadList(String meId, List<String> threadIds) {
    if (threadIds.isEmpty()) {
      return List.of();
    }
    List<Message> all = messages.findByThreadIdInOrderByThreadIdAscCreatedAtAsc(threadIds);
    Map<String, List<Message>> byThread = new LinkedHashMap<>();
    for (Message m : all) {
      byThread.computeIfAbsent(m.getThreadId(), k -> new ArrayList<>()).add(m);
    }
    Map<String, List<MessageRecipient>> recByMsg = recipientsByMessage(all);
    Map<String, MessageRecipient> mine = myRows(meId, recByMsg);

    Map<String, MailPartyView> partyMap =
        partiesById(visibleParticipantIds(meId, all, recByMsg, null));
    // Which of these messages carry an attachment (for the paperclip indicator).
    Set<String> messagesWithAttachments =
        attachments.findByMessageIdInOrderByCreatedAtAsc(all.stream().map(Message::getId).toList())
            .stream()
            .map(MessageAttachment::getMessageId)
            .collect(Collectors.toSet());
    // Which of these threads the viewer has starred / archived (the per-row flags) — two batched lookups.
    Set<String> starredThreadIds =
        threadStars.findByAccountIdAndThreadIdIn(meId, threadIds).stream()
            .map(ThreadStar::getThreadId)
            .collect(Collectors.toSet());
    Set<String> archivedThreadIds =
        threadArchives.findByAccountIdAndThreadIdIn(meId, threadIds).stream()
            .map(ThreadArchive::getThreadId)
            .collect(Collectors.toSet());
    // The viewer's own labels on each of these threads (batched — no N+1).
    Map<String, List<LabelRef>> labelsByThread = labelsByThread(meId, threadIds);

    List<ThreadListItemView> out = new ArrayList<>();
    for (String threadId : threadIds) {
      List<Message> msgs = byThread.get(threadId);
      if (msgs == null || msgs.isEmpty()) {
        continue;
      }
      List<Message> visible = msgs.stream().filter(m -> visibleToMe(meId, m, mine)).toList();
      if (visible.isEmpty()) {
        continue; // fully hidden by me — defensively skip (shouldn't appear in the id page)
      }
      Message last = visible.get(visible.size() - 1);
      boolean unread =
          visible.stream()
              .anyMatch(
                  m ->
                      !meId.equals(m.senderAccountId())
                          && mine.get(m.getId()) != null
                          && mine.get(m.getId()).getReadAt() == null);
      boolean hasAttachments =
          visible.stream().anyMatch(m -> messagesWithAttachments.contains(m.getId()));
      List<MailPartyView> participants = otherParties(meId, msgs, recByMsg, partyMap);
      out.add(
          new ThreadListItemView(
              threadId,
              msgs.get(0).getSubject(),
              snippet(last.getBody()),
              last.getCreatedAt().toString(),
              participants,
              visible.size(),
              unread,
              hasAttachments,
              starredThreadIds.contains(threadId),
              archivedThreadIds.contains(threadId),
              labelsByThread.getOrDefault(threadId, List.of())));
    }
    return out;
  }

  private void setReadState(String meId, String threadId, boolean read) {
    List<Message> msgs = messages.findByThreadIdOrderByCreatedAtAsc(threadId);
    if (msgs.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Thread not found");
    }
    Map<String, List<MessageRecipient>> recByMsg = recipientsByMessage(msgs);
    Map<String, MessageRecipient> mine = myRows(meId, recByMsg);
    if (!isParticipant(meId, msgs, recByMsg)) {
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
      String meId, List<Message> msgs, Map<String, List<MessageRecipient>> recByMsg) {
    for (Message m : msgs) {
      if (meId.equals(m.senderAccountId())) {
        return true;
      }
      for (MessageRecipient r : recByMsg.getOrDefault(m.getId(), List.of())) {
        if (meId.equals(r.recipientAccountId())) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * The account ids a VIEWER may see across the thread (senders + TO/CC recipients + only the BCC rows
   * visible to them), optionally excluding one id. This is the BCC-privacy boundary: a BCC recipient that
   * isn't the viewer or the message's sender is never included, so it can never reach the response.
   */
  private Set<String> visibleParticipantIds(
      String meId,
      List<Message> msgs,
      Map<String, List<MessageRecipient>> recByMsg,
      String exclude) {
    Set<String> ids = new LinkedHashSet<>();
    for (Message m : msgs) {
      String sender = m.senderAccountId();
      if (exclude == null || !exclude.equals(sender)) {
        ids.add(sender);
      }
      for (MessageRecipient r : recByMsg.getOrDefault(m.getId(), List.of())) {
        if (r.getRecipientType() == RecipientType.BCC && !bccVisible(meId, m, r)) {
          continue; // another recipient's BCC — hidden from this viewer
        }
        String rid = r.recipientAccountId();
        if (exclude == null || !exclude.equals(rid)) {
          ids.add(rid);
        }
      }
    }
    return ids;
  }

  private List<MailPartyView> otherParties(
      String meId,
      List<Message> msgs,
      Map<String, List<MessageRecipient>> recByMsg,
      Map<String, MailPartyView> partyMap) {
    return visibleParticipantIds(meId, msgs, recByMsg, meId).stream()
        .map(partyMap::get)
        .filter(Objects::nonNull)
        .collect(Collectors.toList());
  }

  private boolean visibleToMe(String meId, Message m, Map<String, MessageRecipient> mine) {
    if (meId.equals(m.senderAccountId())) {
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

  private Map<String, List<MessageAttachment>> attachmentsByMessage(List<Message> msgs) {
    List<String> ids = msgs.stream().map(Message::getId).toList();
    Map<String, List<MessageAttachment>> map = new HashMap<>();
    if (ids.isEmpty()) {
      return map;
    }
    for (MessageAttachment a : attachments.findByMessageIdInOrderByCreatedAtAsc(ids)) {
      map.computeIfAbsent(a.getMessageId(), k -> new ArrayList<>()).add(a);
    }
    return map;
  }

  private List<AttachmentView> attachmentViews(List<MessageAttachment> list) {
    if (list == null || list.isEmpty()) {
      return List.of();
    }
    return list.stream()
        .map(a -> new AttachmentView(a.getId(), a.getFileName(), a.getContentType(), a.getSizeBytes()))
        .collect(Collectors.toList());
  }

  private Map<String, MessageRecipient> myRows(String meId, Map<String, List<MessageRecipient>> recByMsg) {
    Map<String, MessageRecipient> mine = new HashMap<>();
    for (List<MessageRecipient> rs : recByMsg.values()) {
      for (MessageRecipient r : rs) {
        if (meId.equals(r.recipientAccountId())) {
          mine.put(r.getMessageId(), r);
        }
      }
    }
    return mine;
  }

  // --- Principals + parties -----------------------------------------------------

  /** Resolve the acting principal to a mail participant; an employee needs an assigned mailbox (§8). */
  private MailParticipant resolveActor(IhrmsPrincipal actor) {
    if (actor instanceof IhrmsPrincipal.User u) {
      User user = users.findById(u.userId()).orElseThrow(this::unauthorized);
      return MailParticipant.user(user);
    }
    IhrmsPrincipal.Employee e = (IhrmsPrincipal.Employee) actor;
    Employee employee = employees.findById(e.employeeId()).orElseThrow(this::unauthorized);
    if (employee.getMailAddress() == null) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not have a mailbox yet");
    }
    return MailParticipant.employee(employee);
  }

  /** Resolve a recipient id (a user OR a credentialed employee) to a participant, or 404. */
  private MailParticipant resolveRecipient(String id) {
    User u = users.findById(id).orElse(null);
    if (u != null) {
      return MailParticipant.user(u);
    }
    Employee e = employees.findById(id).filter(x -> x.getMailAddress() != null).orElse(null);
    if (e != null) {
      return MailParticipant.employee(e);
    }
    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Recipient not found");
  }

  private static boolean isUser(MailParticipant p) {
    return "USER".equals(p.type());
  }

  private static AuditActor actorOf(MailParticipant me) {
    return new AuditActor(me.type(), me.id(), me.companyId());
  }

  private void setSender(Message message, MailParticipant me) {
    if (isUser(me)) {
      message.setSenderUserId(me.id());
    } else {
      message.setSenderEmployeeId(me.id());
    }
  }

  private void saveRecipient(String messageId, MailParticipant to, RecipientType type) {
    MessageRecipient row = new MessageRecipient();
    row.setMessageId(messageId);
    if (isUser(to)) {
      row.setRecipientUserId(to.id());
    } else {
      row.setRecipientEmployeeId(to.id());
    }
    row.setRecipientType(type);
    recipients.save(row);
  }

  /**
   * Resurface-on-reply for ARCHIVE (§8, Gmail-style): a newly delivered message clears each recipient's
   * archive of the thread — so an archived thread returns to their Inbox on new inbound activity. Runs in the
   * SAME transaction as delivery (like the soft-delete resurface, where a fresh non-deleted recipient row
   * naturally re-lists the thread). A no-op for recipients who hadn't archived it. The SENDER's own archive
   * is intentionally left as-is (mirrors the soft-delete model, where a sender's deleted copies stay hidden).
   */
  private void clearRecipientArchives(String threadId, Collection<MailParticipant> recipients) {
    if (recipients.isEmpty()) {
      return;
    }
    List<String> ids = recipients.stream().map(MailParticipant::id).distinct().toList();
    threadArchives.deleteByThreadIdAndAccountIdIn(threadId, ids);
  }

  private void auditSent(
      MailParticipant me, Message message, int recipientCount, int attachmentCount, String ip) {
    audit.record(
        actorOf(me),
        "MAIL_SENT",
        "Message",
        message.getId(),
        Map.of(
            "threadId", message.getThreadId(),
            "recipientCount", recipientCount,
            "subject", message.getSubject(),
            "attachmentCount", attachmentCount),
        ip);
  }

  // --- Recipient sets: typing, authorization, reply derivation --------------------

  /** Build the ordered TO/CC/BCC map, de-duping across fields (first placement wins → TO beats CC beats BCC). */
  private LinkedHashMap<String, RecipientType> typedRecipients(
      List<String> to, List<String> cc, List<String> bcc) {
    LinkedHashMap<String, RecipientType> map = new LinkedHashMap<>();
    addTyped(map, to, RecipientType.TO);
    addTyped(map, cc, RecipientType.CC);
    addTyped(map, bcc, RecipientType.BCC);
    return map;
  }

  private void addTyped(
      LinkedHashMap<String, RecipientType> map, List<String> ids, RecipientType type) {
    if (ids == null) {
      return;
    }
    for (String id : ids) {
      if (id != null && !id.isBlank()) {
        map.putIfAbsent(id.trim(), type);
      }
    }
  }

  /**
   * Resolve every recipient id and re-check it through the ONE send graph. If ANY is disallowed the whole
   * send is rejected (403) — never silently dropped. The actor's team keys are resolved once (no N+1).
   */
  private Map<String, MailParticipant> resolveAndAuthorize(MailParticipant me, Set<String> ids) {
    Set<String> myKeys = authz.mailTeamKeys(me);
    Map<String, MailParticipant> resolved = new LinkedHashMap<>();
    for (String id : ids) {
      if (me.id().equals(id)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You cannot message yourself");
      }
      MailParticipant to = resolveRecipient(id);
      if (!authz.canSendMail(me, myKeys, to, authz.mailTeamKeys(to))) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "You are not allowed to message one of the recipients");
      }
      resolved.put(id, to);
    }
    return resolved;
  }

  /**
   * Reply target: the sender of the latest message NOT authored by me (whom I am replying to). If I
   * authored every message so far (following up on my own thread), reply continues to the recipients I
   * addressed as TO — never a BCC.
   */
  private Set<String> replyToSender(
      String meId, List<Message> msgs, Map<String, List<MessageRecipient>> recByMsg) {
    for (int i = msgs.size() - 1; i >= 0; i--) {
      String sender = msgs.get(i).senderAccountId();
      if (!meId.equals(sender)) {
        return new LinkedHashSet<>(List.of(sender));
      }
    }
    LinkedHashSet<String> to = new LinkedHashSet<>();
    for (Message m : msgs) {
      for (MessageRecipient r : recByMsg.getOrDefault(m.getId(), List.of())) {
        if (r.getRecipientType() == RecipientType.TO && !meId.equals(r.recipientAccountId())) {
          to.add(r.recipientAccountId());
        }
      }
    }
    return to;
  }

  /** Reply-all: every sender + TO/CC recipient across the thread, minus me, NEVER any BCC. De-duped. */
  private Set<String> replyAllRecipientIds(
      String meId, List<Message> msgs, Map<String, List<MessageRecipient>> recByMsg) {
    LinkedHashSet<String> ids = new LinkedHashSet<>();
    for (Message m : msgs) {
      ids.add(m.senderAccountId());
      for (MessageRecipient r : recByMsg.getOrDefault(m.getId(), List.of())) {
        if (r.getRecipientType() != RecipientType.BCC) {
          ids.add(r.recipientAccountId());
        }
      }
    }
    ids.remove(meId);
    return ids;
  }

  /** Whether a BCC row is visible to this viewer: only the message's sender, or the BCC recipient itself. */
  private boolean bccVisible(String meId, Message m, MessageRecipient r) {
    return meId.equals(m.senderAccountId()) || meId.equals(r.recipientAccountId());
  }

  /** Recipient parties of one type on a message (BCC callers must pre-filter for visibility). */
  private List<MailPartyView> partiesOfType(
      List<MessageRecipient> rs, RecipientType type, Map<String, MailPartyView> partyMap) {
    return rs.stream()
        .filter(r -> r.getRecipientType() == type)
        .map(r -> partyMap.get(r.recipientAccountId()))
        .filter(Objects::nonNull)
        .collect(Collectors.toList());
  }

  private static String snippet(String body) {
    String s = body == null ? "" : body.strip().replaceAll("\\s+", " ");
    return s.length() <= SNIPPET ? s : s.substring(0, SNIPPET - 1) + "…";
  }

  /** Resolve a set of account ids to display parties — each id is a User OR an Employee (§8). */
  private Map<String, MailPartyView> partiesById(Collection<String> ids) {
    Map<String, MailPartyView> map = new HashMap<>();
    if (ids.isEmpty()) {
      return map;
    }
    Map<String, User> us = usersById(ids);
    Map<String, String> domains = domainsFor(us.values());
    us.values().forEach(u -> map.put(u.getId(), party(u, domains)));
    List<String> rest = ids.stream().filter(id -> !map.containsKey(id)).toList();
    if (!rest.isEmpty()) {
      for (Employee e : employees.findAllById(rest)) {
        map.put(e.getId(), employeeParty(e));
      }
    }
    return map;
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

  /** The display address {@code localpart@domain} for a staff account — platform on {@code @ihrms}. */
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

  /** An employee's display party — their assigned mailbox address; no role. */
  private MailPartyView employeeParty(Employee e) {
    return new MailPartyView(e.getId(), e.getFullName(), e.getMailAddress(), null);
  }

  private Map<String, User> usersById(Iterable<String> ids) {
    Map<String, User> map = new HashMap<>();
    for (User u : users.findAllById(ids)) {
      map.put(u.getId(), u);
    }
    return map;
  }

  private ResponseStatusException unauthorized() {
    return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unknown account");
  }
}
