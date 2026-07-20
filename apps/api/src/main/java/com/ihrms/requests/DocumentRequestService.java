package com.ihrms.requests;

import com.ihrms.audit.AuditActor;
import com.ihrms.audit.AuditService;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.auth.MailService;
import com.ihrms.domain.enums.RequestStatus;
import com.ihrms.domain.model.DocumentRequest;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.RequestDocument;
import com.ihrms.domain.model.Team;
import com.ihrms.domain.repository.DocumentRequestRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.RequestDocumentRepository;
import com.ihrms.domain.repository.TeamRepository;
import com.ihrms.mail.InternalMailService;
import com.ihrms.mail.MailAttachments;
import com.ihrms.mail.dto.MailDtos.SendMessageRequest;
import com.ihrms.push.PushService;
import com.ihrms.push.PushService.PrincipalRef;
import com.ihrms.requests.dto.DocumentRequestDtos.DocumentRequestView;
import com.ihrms.requests.dto.DocumentRequestDtos.MyRequestsPage;
import com.ihrms.requests.dto.DocumentRequestDtos.PresignedView;
import com.ihrms.requests.dto.DocumentRequestDtos.RequestDocumentView;
import com.ihrms.requests.dto.DocumentRequestDtos.RequestUpload;
import com.ihrms.requests.dto.DocumentRequestDtos.ResolveRequest;
import com.ihrms.requests.dto.DocumentRequestDtos.SubmitDocumentRequest;
import com.ihrms.requests.dto.DocumentRequestDtos.TeamRequestRow;
import com.ihrms.requests.dto.DocumentRequestDtos.TeamRequestsPage;
import com.ihrms.requests.dto.DocumentRequestDtos.UploadDocumentRequest;
import com.ihrms.storage.StorageService;
import com.ihrms.support.Hashing;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * HR/Accounts document requests — Accounts side (§8d). A credentialed employee submits a request that
 * routes to the Accountant on the employee's onboarding-HR's team (the resolved routee, stored on the row
 * — the SAME resolution as leave/approvals). The Accountant picks it up, uploads the requested file(s) via
 * the S3 presigned upload→confirm handshake, and resolves it. Employee↔team-accountant is NOT in the mail
 * graph (§8), so — without weakening it — notifications go via the request queue/history + a best-effort
 * OS push, plus a dev-logged employee email on resolve (the leave-consistent, non-silent substitute).
 */
@Service
public class DocumentRequestService {

  private static final Logger log = LoggerFactory.getLogger(DocumentRequestService.class);

  /** A resolve may bind at most this many files at once (a runaway guard; each is individually validated). */
  private static final int MAX_DOCUMENTS_PER_REQUEST = 20;

  private final DocumentRequestRepository requests;
  private final RequestDocumentRepository requestDocuments;
  private final EmployeeRepository employees;
  private final TeamRepository teams;
  private final StorageService storage;
  private final MailAttachments attachmentRules;
  private final MailService mail;
  private final InternalMailService internalMail;
  private final PushService push;
  private final AuditService audit;

  public DocumentRequestService(
      DocumentRequestRepository requests,
      RequestDocumentRepository requestDocuments,
      EmployeeRepository employees,
      TeamRepository teams,
      StorageService storage,
      MailAttachments attachmentRules,
      MailService mail,
      InternalMailService internalMail,
      PushService push,
      AuditService audit) {
    this.requests = requests;
    this.requestDocuments = requestDocuments;
    this.employees = employees;
    this.teams = teams;
    this.storage = storage;
    this.attachmentRules = attachmentRules;
    this.mail = mail;
    this.internalMail = internalMail;
    this.push = push;
    this.audit = audit;
  }

  // --- Employee ------------------------------------------------------------

  @Transactional
  public DocumentRequestView submit(IhrmsPrincipal actor, SubmitDocumentRequest req, String ip) {
    Employee me = requireCredentialedEmployee(actor);
    String accountantUserId = resolveAccountant(me);

    DocumentRequest request = new DocumentRequest();
    request.setEmployeeId(me.getId());
    request.setCompanyId(me.getCompanyId());
    request.setAccountantUserId(accountantUserId);
    request.setRequestType(req.requestType());
    request.setNote(clean(req.note()));
    request.setStatus(RequestStatus.SUBMITTED);
    requests.save(request);

    audit.record(
        AuditActor.from(actor),
        "REQUEST_SUBMITTED",
        "DocumentRequest",
        request.getId(),
        Map.of("accountantUserId", accountantUserId, "requestType", req.requestType().name()),
        ip);
    // The accountant's durable record is their /requests/team queue; a best-effort OS push pings them
    // AFTER this tx commits — see pushAccountantAfterSubmit (employee↔accountant is not in the mail graph).
    return view(request, List.of());
  }

  @Transactional(readOnly = true)
  public MyRequestsPage myRequests(IhrmsPrincipal actor, Pageable pageable) {
    Employee me = requireCredentialedEmployee(actor);
    Page<DocumentRequest> page = requests.findByEmployeeIdOrderByCreatedAtDesc(me.getId(), pageable);
    Map<String, List<RequestDocument>> docs = boundDocsFor(page.getContent());
    List<DocumentRequestView> content =
        page.getContent().stream()
            .map(r -> view(r, docs.getOrDefault(r.getId(), List.of())))
            .toList();
    return new MyRequestsPage(
        content, page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
  }

  @Transactional
  public DocumentRequestView cancel(IhrmsPrincipal actor, String id, String ip) {
    Employee me = requireCredentialedEmployee(actor);
    DocumentRequest request = requests.findById(id).orElseThrow(DocumentRequestService::notFound);
    if (!request.getEmployeeId().equals(me.getId())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your request");
    }
    if (!request.isSubmitted()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "This request is already " + request.getStatus().name().toLowerCase().replace('_', ' '));
    }
    request.setStatus(RequestStatus.CANCELLED);
    requests.save(request);
    audit.record(
        AuditActor.from(actor), "REQUEST_CANCELLED", "DocumentRequest", request.getId(), Map.of(), ip);
    return view(request, List.of());
  }

  // --- Accountant (team-scope) ---------------------------------------------

  @Transactional(readOnly = true)
  public TeamRequestsPage teamQueue(IhrmsPrincipal.User accountant, String status, Pageable pageable) {
    RequestStatus statusFilter = parseStatus(status);
    Specification<DocumentRequest> spec =
        (root, q, cb) -> {
          List<Predicate> p = new ArrayList<>();
          p.add(cb.equal(root.get("accountantUserId"), accountant.userId())); // routed to this accountant
          p.add(cb.equal(root.get("companyId"), accountant.companyId())); // tenant filter
          if (statusFilter != null) p.add(cb.equal(root.get("status"), statusFilter));
          return cb.and(p.toArray(new Predicate[0]));
        };
    Page<DocumentRequest> page = requests.findAll(spec, pageable);
    Map<String, Employee> byId = employeeMap(page.getContent());
    Map<String, String> teamByHr = teamNamesFor(accountant);
    Map<String, List<RequestDocument>> docs = boundDocsFor(page.getContent());
    List<TeamRequestRow> rows =
        page.getContent().stream()
            .map(
                r ->
                    teamRow(
                        r,
                        byId.get(r.getEmployeeId()),
                        teamByHr,
                        docs.getOrDefault(r.getId(), List.of())))
            .toList();
    return new TeamRequestsPage(
        rows, page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
  }

  @Transactional
  public TeamRequestRow pickUp(IhrmsPrincipal.User accountant, String id, String ip) {
    DocumentRequest request = loadRoutedRequest(accountant, id);
    if (!request.isSubmitted()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "This request is already " + request.getStatus().name().toLowerCase().replace('_', ' '));
    }
    request.setStatus(RequestStatus.IN_PROGRESS);
    request.setPickedUpAt(Instant.now());
    requests.save(request);
    audit.record(
        AuditActor.from(accountant), "REQUEST_PICKED_UP", "DocumentRequest", request.getId(), Map.of(), ip);
    return teamRow(request, employees.findById(request.getEmployeeId()).orElse(null),
        teamNamesFor(accountant), List.of());
  }

  /** Step 1 of the fulfilment handshake: validate the file, allocate a key + DRAFT, return a presigned PUT. */
  @Transactional
  public RequestUpload requestDocumentUpload(
      IhrmsPrincipal.User accountant, String id, UploadDocumentRequest input, String ip) {
    DocumentRequest request = loadRoutedRequest(accountant, id);
    if (!request.isOpen()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "This request is not open for fulfilment");
    }
    String type = attachmentRules.validate(input.fileName(), input.contentType(), input.sizeBytes());
    // The file lives under the EMPLOYEE's record (tenant-consistent), uploaded by the accountant.
    String key = storage.buildKey(request.getCompanyId(), request.getEmployeeId(), "requests", input.fileName());
    RequestDocument draft = new RequestDocument();
    draft.setRequestId(request.getId());
    draft.setFileName(input.fileName());
    draft.setContentType(type);
    draft.setSizeBytes(input.sizeBytes());
    draft.setStorageKey(key);
    draft.setUploadedByUserId(accountant.userId());
    requestDocuments.save(draft);

    String uploadUrl = storage.presignedPutUrl(key, type, MailAttachments.UPLOAD_TTL_SECONDS);
    audit.record(
        AuditActor.from(accountant),
        "REQUEST_DOCUMENT_UPLOAD_REQUESTED",
        "RequestDocument",
        draft.getId(),
        Map.of("requestId", request.getId(), "fileName", input.fileName(), "contentType", type),
        ip);
    return new RequestUpload(
        draft.getId(), uploadUrl, "PUT", Map.of("Content-Type", type), MailAttachments.UPLOAD_TTL_SECONDS);
  }

  /** Step 2: bind the uploaded file(s) to the request (sha256 + limits re-enforced) and mark it RESOLVED. */
  @Transactional
  public TeamRequestRow resolve(
      IhrmsPrincipal.User accountant, String id, ResolveRequest req, String ip) {
    DocumentRequest request = loadRoutedRequest(accountant, id);
    if (!request.isOpen()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "This request is not open for fulfilment");
    }
    List<String> ids = req.documentIds() == null ? List.of() : req.documentIds().stream().distinct().toList();
    if (ids.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Upload at least one document before resolving");
    }
    if (ids.size() > MAX_DOCUMENTS_PER_REQUEST) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "At most " + MAX_DOCUMENTS_PER_REQUEST + " documents per request");
    }
    List<RequestDocument> bound = new ArrayList<>();
    for (String docId : ids) {
      RequestDocument doc =
          requestDocuments
              .findById(docId)
              .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown document"));
      if (!request.getId().equals(doc.getRequestId())
          || !accountant.userId().equals(doc.getUploadedByUserId())
          || doc.getSha256() != null) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That document is not available");
      }
      byte[] bytes = storage.getObjectBytes(doc.getStorageKey());
      if (bytes == null || bytes.length == 0) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A document upload did not complete");
      }
      // Re-validate against the ACTUAL stored bytes (size) + declared type — never trust the client.
      attachmentRules.validate(doc.getFileName(), doc.getContentType(), bytes.length);
      doc.setSizeBytes(bytes.length);
      doc.setSha256(Hashing.sha256Hex(bytes));
      bound.add(doc);
    }
    requestDocuments.saveAll(bound);

    request.setStatus(RequestStatus.RESOLVED);
    request.setResolvedAt(Instant.now());
    request.setResolveNote(clean(req.note()));
    requests.save(request);

    audit.record(
        AuditActor.from(accountant),
        "REQUEST_RESOLVED",
        "DocumentRequest",
        request.getId(),
        Map.of("employeeId", request.getEmployeeId(), "documentCount", bound.size()),
        ip);
    for (RequestDocument doc : bound) {
      audit.record(
          AuditActor.from(accountant),
          "REQUEST_DOCUMENT_UPLOADED",
          "RequestDocument",
          doc.getId(),
          Map.of("requestId", request.getId(), "fileName", doc.getFileName(), "sha256", doc.getSha256()),
          ip);
    }
    // The employee is notified (email + push) by the controller AFTER this tx commits — see
    // notifyEmployeeAfterResolve.
    return teamRow(request, employees.findById(request.getEmployeeId()).orElse(null),
        teamNamesFor(accountant), bound);
  }

  // --- Fulfilment download (employee-owner OR routed accountant) -----------

  @Transactional
  public PresignedView download(IhrmsPrincipal actor, String id, String docId, String ip) {
    DocumentRequest request = requests.findById(id).orElseThrow(DocumentRequestService::notFound);
    // Authorize: the request's OWN employee, or the routed accountant (their team). Anyone else 403.
    if (actor instanceof IhrmsPrincipal.Employee) {
      Employee me = requireCredentialedEmployee(actor);
      if (!request.getEmployeeId().equals(me.getId())) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot download this document");
      }
    } else if (actor instanceof IhrmsPrincipal.User user) {
      if (!request.getAccountantUserId().equals(user.userId())
          || !request.getCompanyId().equals(user.companyId())) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot download this document");
      }
    } else {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot download this document");
    }

    RequestDocument doc =
        requestDocuments
            .findById(docId)
            .filter(d -> d.getRequestId().equals(request.getId()) && d.getSha256() != null)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));

    String url = storage.presignedGetUrl(doc.getStorageKey(), MailAttachments.DOWNLOAD_TTL_SECONDS);
    audit.record(
        AuditActor.from(actor),
        "REQUEST_DOCUMENT_DOWNLOADED",
        "RequestDocument",
        doc.getId(),
        Map.of("requestId", request.getId(), "fileName", doc.getFileName()),
        ip);
    return new PresignedView(url, MailAttachments.DOWNLOAD_TTL_SECONDS);
  }

  // --- Best-effort notifications (controller-after-commit, §8d) ------------
  //
  // The send graph now includes the employee↔their-team-accountant edge (§8), so these go through the
  // ordinary canSendMail-guarded InternalMailService path — a real repliable thread, audited MAIL_SENT —
  // exactly like the leave auto-mails. Each is ALSO accompanied by the existing best-effort OS push, and
  // the employee still gets the dev-logged email on resolve. Run from the CONTROLLER, AFTER the request tx
  // commits (so the send opens its own committing tx), and best-effort: mail and push each in their own
  // try/catch so a hiccup in either can never roll back — or fail — the request action. Exactly one mail
  // per submit / per resolve.

  /** Employee → routed Accountant on submit: a real internal mail + the OS push. Best-effort. */
  public void notifyAccountantAfterSubmit(IhrmsPrincipal actor, String requestId, String ip) {
    DocumentRequest request = requests.findById(requestId).orElse(null);
    if (request == null) {
      return;
    }
    Employee me = employees.findById(request.getEmployeeId()).orElse(null);
    String who = me != null ? me.getFullName() : "An employee";
    // (1) Real internal mail, employee → the routed accountant (the §8d edge permits it).
    try {
      String team = teamNameFor(me);
      String subject = "Document request: " + who + " (" + typeLabel(request) + ")";
      StringBuilder body = new StringBuilder();
      body.append(who).append(team == null ? "" : " from team " + team).append(" requested ")
          .append(typeLabel(request)).append(".\n");
      if (request.getNote() != null && !request.getNote().isBlank()) {
        body.append("Note: ").append(request.getNote().trim()).append("\n");
      }
      body.append("\nOpen the Requests inbox to pick it up.");
      internalMail.send(actor, mailTo(request.getAccountantUserId(), subject, body.toString()), ip);
    } catch (RuntimeException e) {
      log.warn("Request submit mail skipped (best-effort): {}", e.getMessage());
    }
    // (2) OS push to the accountant (kept).
    try {
      push.sendToPrincipal(
          PrincipalRef.forUser(request.getAccountantUserId(), request.getCompanyId()),
          "New document request",
          who + " requested " + label(request) + note(request),
          "/accountant/requests");
    } catch (RuntimeException e) {
      log.warn("Request submit push skipped (best-effort): {}", e.getMessage());
    }
  }

  /** Accountant → employee on resolve: a real internal mail + the dev-logged email + the OS push. Best-effort. */
  public void notifyEmployeeAfterResolve(IhrmsPrincipal actor, String requestId, String ip) {
    DocumentRequest request = requests.findById(requestId).orElse(null);
    if (request == null || request.getStatus() != RequestStatus.RESOLVED) {
      return;
    }
    Employee me = employees.findById(request.getEmployeeId()).orElse(null);
    if (me == null) {
      return;
    }
    // (1) Real internal mail, the resolving accountant → the employee (the §8d edge permits it).
    try {
      String subject = "Your " + typeLabel(request) + " request is ready";
      String body =
          "Your " + typeLabel(request) + " request has been resolved. The document(s) are ready to "
              + "download from your HR/Accounts Requests page.";
      internalMail.send(actor, mailTo(me.getId(), subject, body), ip);
    } catch (RuntimeException e) {
      log.warn("Request resolve mail skipped (best-effort): {}", e.getMessage());
    }
    // (2) The employee-facing dev-logged email + OS push (kept).
    try {
      mail.sendDocumentRequestResolved(me.getEmail(), request.getRequestType().name());
      push.sendToPrincipal(
          PrincipalRef.forEmployee(me.getId(), request.getCompanyId()),
          "Your document request is ready",
          "Your " + label(request) + " request has been resolved — the document(s) are ready to download.",
          "/workspace/requests");
    } catch (RuntimeException e) {
      log.warn("Request resolve notification skipped (best-effort): {}", e.getMessage());
    }
  }

  /** The employee's team name (their onboarding-HR's team), for the courtesy mail; null if unresolved. */
  private String teamNameFor(Employee employee) {
    if (employee == null || employee.getOnboardingHrId() == null) {
      return null;
    }
    return teams
        .findByCompanyIdAndHrUserId(employee.getCompanyId(), employee.getOnboardingHrId())
        .map(Team::getName)
        .orElse(null);
  }

  private static SendMessageRequest mailTo(String toId, String subject, String body) {
    return new SendMessageRequest(List.of(toId), List.of(), List.of(), subject, body, List.of());
  }

  private static String typeLabel(DocumentRequest r) {
    return r.getRequestType().name().replace('_', ' ');
  }

  // --- helpers -------------------------------------------------------------

  /** Routee = employee.onboardingHr → that HR's team → team.accountant. 409 if no accountant assigned. */
  private String resolveAccountant(Employee me) {
    String noAccountant = "No accountant assigned to your team";
    if (me.getOnboardingHrId() == null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, noAccountant);
    }
    Team team =
        teams
            .findByCompanyIdAndHrUserId(me.getCompanyId(), me.getOnboardingHrId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, noAccountant));
    String accountantUserId = team.getAccountantUserId();
    if (accountantUserId == null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, noAccountant);
    }
    return accountantUserId;
  }

  private DocumentRequest loadRoutedRequest(IhrmsPrincipal.User accountant, String id) {
    DocumentRequest request = requests.findById(id).orElseThrow(DocumentRequestService::notFound);
    if (!request.getAccountantUserId().equals(accountant.userId())
        || !request.getCompanyId().equals(accountant.companyId())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This request is not routed to you");
    }
    return request;
  }

  private Employee requireCredentialedEmployee(IhrmsPrincipal actor) {
    if (!(actor instanceof IhrmsPrincipal.Employee principal)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Requests are for employees");
    }
    Employee employee =
        employees
            .findById(principal.employeeId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unknown account"));
    if (employee.getMailAddress() == null) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not have request access");
    }
    return employee;
  }

  /** hrUserId → team name for the accountant's team(s), so a queue row can show the employee's team. */
  private Map<String, String> teamNamesFor(IhrmsPrincipal.User accountant) {
    return teams.findByAccountantUserId(accountant.userId()).stream()
        .filter(t -> accountant.companyId() != null && accountant.companyId().equals(t.getCompanyId()))
        .filter(t -> t.getHrUserId() != null)
        .collect(Collectors.toMap(Team::getHrUserId, Team::getName, (a, b) -> a));
  }

  private Map<String, Employee> employeeMap(List<DocumentRequest> rows) {
    Set<String> ids = rows.stream().map(DocumentRequest::getEmployeeId).collect(Collectors.toSet());
    if (ids.isEmpty()) {
      return Map.of();
    }
    return employees.findAllById(ids).stream().collect(Collectors.toMap(Employee::getId, e -> e));
  }

  /** Bound (fulfilled) files for a page of requests, grouped by requestId (unbound drafts excluded). */
  private Map<String, List<RequestDocument>> boundDocsFor(List<DocumentRequest> rows) {
    List<String> ids = rows.stream().map(DocumentRequest::getId).toList();
    if (ids.isEmpty()) {
      return Map.of();
    }
    return requestDocuments.findByRequestIdInAndSha256IsNotNullOrderByCreatedAtAsc(ids).stream()
        .collect(Collectors.groupingBy(RequestDocument::getRequestId));
  }

  private DocumentRequestView view(DocumentRequest r, List<RequestDocument> docs) {
    return new DocumentRequestView(
        r.getId(),
        r.getRequestType(),
        r.getNote(),
        r.getStatus(),
        r.getResolveNote(),
        iso(r.getPickedUpAt()),
        iso(r.getResolvedAt()),
        iso(r.getCreatedAt()),
        docs.stream().map(DocumentRequestService::docView).toList());
  }

  private TeamRequestRow teamRow(
      DocumentRequest r, Employee e, Map<String, String> teamByHr, List<RequestDocument> docs) {
    String teamName = e == null || e.getOnboardingHrId() == null ? null : teamByHr.get(e.getOnboardingHrId());
    return new TeamRequestRow(
        r.getId(),
        r.getEmployeeId(),
        e == null ? null : e.getEmployeeCode(),
        e == null ? null : e.getFullName(),
        teamName,
        r.getRequestType(),
        r.getNote(),
        r.getStatus(),
        r.getResolveNote(),
        iso(r.getPickedUpAt()),
        iso(r.getResolvedAt()),
        iso(r.getCreatedAt()),
        docs.stream().map(DocumentRequestService::docView).toList());
  }

  private static RequestDocumentView docView(RequestDocument d) {
    return new RequestDocumentView(
        d.getId(), d.getFileName(), d.getContentType(), d.getSizeBytes(), iso(d.getCreatedAt()));
  }

  private RequestStatus parseStatus(String status) {
    if (status == null || status.isBlank()) {
      return null;
    }
    try {
      return RequestStatus.valueOf(status);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown status: " + status);
    }
  }

  private static String label(DocumentRequest r) {
    return r.getRequestType().name().replace('_', ' ').toLowerCase();
  }

  private static String note(DocumentRequest r) {
    return r.getNote() == null || r.getNote().isBlank() ? "" : " (" + r.getNote().trim() + ")";
  }

  private static String clean(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }

  private static String iso(Instant t) {
    return t == null ? null : t.toString();
  }

  private static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found");
  }
}
