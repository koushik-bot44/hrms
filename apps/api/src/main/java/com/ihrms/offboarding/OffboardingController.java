package com.ihrms.offboarding;

import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.domain.enums.OffboardingDocType;
import com.ihrms.offboarding.dto.OffboardingDocDtos.ClearanceUpdateRequest;
import com.ihrms.offboarding.dto.OffboardingDocDtos.ClearanceView;
import com.ihrms.offboarding.dto.OffboardingDocDtos.RecordDocuments;
import com.ihrms.offboarding.dto.OffboardingDocDtos.SendBackRequest;
import com.ihrms.offboarding.dto.OffboardingDocDtos.SendDocumentsRequest;
import com.ihrms.offboarding.dto.OffboardingDocDtos.SendDocumentsResult;
import com.ihrms.offboarding.dto.OffboardingDtos.CancelRequest;
import com.ihrms.offboarding.dto.OffboardingDtos.InitiateRequest;
import com.ihrms.offboarding.dto.OffboardingDtos.OffboardingCaseResponse;
import com.ihrms.offboarding.dto.OffboardingDtos.OffboardingCaseView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * HR-facing offboarding endpoints (§Offboarding). Stage 1: initiate/cancel/read the case. Stage 2: send/verify/
 * send-back the employee documents + the HR-side clearance checklist. Initiate/cancel/document-writes/clearance
 * are HR, onboarding-HR-scoped; the case + documents READ are opened to the record-view viewers
 * (HR/COMPANY_ADMIN/SUPER_ADMIN via the URL rule + canAccessEmployee). Notifications fire post-commit.
 */
@RestController
@RequestMapping("/employees/{id}/offboarding")
public class OffboardingController {

  private final OffboardingService offboarding;
  private final OffboardingDocumentService documents;
  private final OffboardingClearanceService clearance;
  private final OffboardingLetterService letters;
  private final com.ihrms.requests.DocumentRequestService requestFulfilment;

  public OffboardingController(
      OffboardingService offboarding,
      OffboardingDocumentService documents,
      OffboardingClearanceService clearance,
      OffboardingLetterService letters,
      com.ihrms.requests.DocumentRequestService requestFulfilment) {
    this.offboarding = offboarding;
    this.documents = documents;
    this.clearance = clearance;
    this.letters = letters;
    this.requestFulfilment = requestFulfilment;
  }

  @PostMapping("/initiate")
  @PreAuthorize("hasRole('HR')")
  @ResponseStatus(HttpStatus.CREATED)
  public OffboardingCaseView initiate(
      @PathVariable String id,
      @Valid @RequestBody InitiateRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    OffboardingCaseView result = offboarding.initiate(actor, id, body, request.getRemoteAddr());
    offboarding.notifyHierarchyAfterInitiate(id); // post-commit, best-effort
    return result;
  }

  /** The case for the record panel — null when the employee has no case. HR/COMPANY_ADMIN/SUPER_ADMIN. */
  @GetMapping
  @PreAuthorize("hasAnyRole('HR','COMPANY_ADMIN','SUPER_ADMIN')")
  public OffboardingCaseResponse get(
      @PathVariable String id, @AuthenticationPrincipal IhrmsPrincipal.User actor) {
    return new OffboardingCaseResponse(offboarding.forRecord(actor, id));
  }

  @PostMapping("/cancel")
  @PreAuthorize("hasRole('HR')")
  public OffboardingCaseView cancel(
      @PathVariable String id,
      @Valid @RequestBody(required = false) CancelRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    OffboardingCaseView result =
        offboarding.cancel(actor, id, body == null ? new CancelRequest(null) : body, request.getRemoteAddr());
    // Best-effort: nudge the hierarchy that a request was withdrawn (a cancelled case leaves the pending
    // inbox regardless, so this is a courtesy — the audit records whether it was pending).
    offboarding.notifyHierarchyAfterCancel(id);
    return result;
  }

  /** HR completes the offboarding (§3.6 stage 3): case COMPLETED + employee OFFBOARDED. */
  @PostMapping("/complete")
  @PreAuthorize("hasRole('HR')")
  public OffboardingCaseView complete(
      @PathVariable String id,
      @RequestBody(required = false) com.ihrms.offboarding.dto.OffboardingDtos.CancelRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    OffboardingCaseView result =
        offboarding.complete(actor, id, body == null ? null : body.note(), request.getRemoteAddr());
    offboarding.notifyAfterComplete(id); // post-commit, best-effort
    return result;
  }

  // --- Stage 2: documents ---------------------------------------------------

  /** The documents section for the record panel — statuses + the send specs. HR/COMPANY_ADMIN/SUPER_ADMIN. */
  @GetMapping("/documents")
  @PreAuthorize("hasAnyRole('HR','COMPANY_ADMIN','SUPER_ADMIN')")
  public RecordDocuments recordDocuments(
      @PathVariable String id, @AuthenticationPrincipal IhrmsPrincipal.User actor) {
    return documents.recordDocuments(actor, id);
  }

  @PostMapping("/documents/send")
  @PreAuthorize("hasRole('HR')")
  @ResponseStatus(HttpStatus.CREATED)
  public SendDocumentsResult sendDocuments(
      @PathVariable String id,
      @RequestBody(required = false) SendDocumentsRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    SendDocumentsResult result = documents.send(actor, id, body, request.getRemoteAddr());
    documents.notifyEmployeeAfterSend(id); // post-commit, best-effort
    return result;
  }

  @PostMapping("/documents/{type}/verify")
  @PreAuthorize("hasRole('HR')")
  public RecordDocuments verifyDocument(
      @PathVariable String id,
      @PathVariable OffboardingDocType type,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    return documents.verify(actor, id, type, request.getRemoteAddr());
  }

  @PostMapping("/documents/{type}/send-back")
  @PreAuthorize("hasRole('HR')")
  public RecordDocuments sendBackDocument(
      @PathVariable String id,
      @PathVariable OffboardingDocType type,
      @RequestBody SendBackRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    RecordDocuments result =
        documents.sendBack(actor, id, type, body == null ? null : body.note(), request.getRemoteAddr());
    documents.notifyEmployeeAfterSendBack(id, type, body == null ? null : body.note());
    return result;
  }

  // --- Stage 2: clearance (HR only) -----------------------------------------

  @GetMapping("/clearance")
  @PreAuthorize("hasRole('HR')")
  public ClearanceView getClearance(
      @PathVariable String id, @AuthenticationPrincipal IhrmsPrincipal.User actor) {
    return clearance.get(actor, id);
  }

  @PutMapping("/clearance")
  @PreAuthorize("hasRole('HR')")
  public ClearanceView putClearance(
      @PathVariable String id,
      @RequestBody ClearanceUpdateRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    return clearance.save(actor, id, body, request.getRemoteAddr());
  }

  // --- Stage 3: letters -----------------------------------------------------
  //
  // The Relieving + Experience letters are COMPANY-ISSUED: HR generates the PDF (below). The employee may have
  // requested one first (reusing the requests machinery); issuing resolves an open request. The upload fulfil
  // handshake remains as a fallback on a request.

  /** The letters section for the record panel — the gate + the two issue specs. HR/COMPANY_ADMIN/SUPER_ADMIN. */
  @GetMapping("/letters")
  @PreAuthorize("hasAnyRole('HR','COMPANY_ADMIN','SUPER_ADMIN')")
  public com.ihrms.offboarding.dto.OffboardingDocDtos.LetterIssuePanel recordLetters(
      @PathVariable String id, @AuthenticationPrincipal IhrmsPrincipal.User actor) {
    return letters.issuePanel(actor, id);
  }

  /** HR previews the substituted letter text before issuing (a dry run — not gated, not persisted). */
  @PostMapping("/letters/{type}/preview")
  @PreAuthorize("hasRole('HR')")
  public com.ihrms.offboarding.dto.OffboardingDocDtos.LetterPreview previewLetter(
      @PathVariable String id,
      @PathVariable com.ihrms.domain.enums.RequestType type,
      @RequestBody(required = false) com.ihrms.offboarding.dto.OffboardingDocDtos.IssueLetterRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.User actor) {
    return letters.preview(actor, id, type, body);
  }

  /** HR issues (generates) the letter PDF — gated on all documents VERIFIED; resolves an open request. */
  @PostMapping("/letters/{type}/issue")
  @PreAuthorize("hasRole('HR')")
  public com.ihrms.offboarding.dto.OffboardingDocDtos.LetterIssuePanel issueLetter(
      @PathVariable String id,
      @PathVariable com.ihrms.domain.enums.RequestType type,
      @RequestBody(required = false) com.ihrms.offboarding.dto.OffboardingDocDtos.IssueLetterRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    com.ihrms.offboarding.dto.OffboardingDocDtos.LetterIssuePanel result =
        letters.issue(actor, id, type, body, request.getRemoteAddr());
    letters.notifyAfterIssue(id, type); // post-commit, best-effort
    return result;
  }

  /** HR fulfil step 1 (upload fallback) — a presigned PUT for the letter file (requests handshake). */
  @PostMapping("/letters/{type}/begin-upload")
  @PreAuthorize("hasRole('HR')")
  public com.ihrms.requests.dto.DocumentRequestDtos.RequestUpload beginLetterUpload(
      @PathVariable String id,
      @PathVariable com.ihrms.domain.enums.RequestType type,
      @jakarta.validation.Valid @RequestBody com.ihrms.requests.dto.DocumentRequestDtos.UploadDocumentRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    String reqId = letters.letterRequestId(actor, id, type);
    return requestFulfilment.requestDocumentUpload(actor, reqId, body, request.getRemoteAddr());
  }

  /** HR fulfil step 2 (upload fallback) — bind the uploaded file + mark the request RESOLVED. */
  @PostMapping("/letters/{type}/resolve")
  @PreAuthorize("hasRole('HR')")
  public com.ihrms.offboarding.dto.OffboardingDocDtos.LetterIssuePanel resolveLetter(
      @PathVariable String id,
      @PathVariable com.ihrms.domain.enums.RequestType type,
      @jakarta.validation.Valid @RequestBody com.ihrms.requests.dto.DocumentRequestDtos.ResolveRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    String reqId = letters.letterRequestId(actor, id, type);
    requestFulfilment.resolve(actor, reqId, body, request.getRemoteAddr());
    return letters.issuePanel(actor, id);
  }
}
