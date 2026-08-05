package com.ihrms.review;

import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.offboarding.OffboardingService;
import com.ihrms.onboarding.OfferService;
import com.ihrms.onboarding.dto.OnboardingDtos.PresignedView;
import com.ihrms.review.dto.ReviewDtos.ApproveRequest;
import com.ihrms.review.dto.ReviewDtos.AssignCredentialsRequest;
import com.ihrms.review.dto.ReviewDtos.AssignCredentialsResult;
import com.ihrms.review.dto.ReviewDtos.DecisionResult;
import com.ihrms.review.dto.ReviewDtos.EmployeeRecordView;
import com.ihrms.review.dto.ReviewDtos.RejectRequest;
import com.ihrms.review.dto.ReviewDtos.RevealedSensitive;
import com.ihrms.review.dto.ReviewDtos.ReviewRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * HR verification & routing workspace (§3.3/§3.4). HR-only; every operation is scoped to the acting
 * HR's own onboarded employees. Verification keys off the INTERNAL employee id;
 * {@code /lookup/{code}} is the §3.4 post-approval records lookup by employee ID.
 */
@RestController
@RequestMapping("/employees")
@PreAuthorize("hasRole('HR')")
public class ReviewController {

  private final ReviewService review;
  private final EmployeeCredentialsService credentials;
  private final OfferService offers;
  private final OffboardingService offboarding;

  public ReviewController(
      ReviewService review,
      EmployeeCredentialsService credentials,
      OfferService offers,
      OffboardingService offboarding) {
    this.review = review;
    this.credentials = credentials;
    this.offers = offers;
    this.offboarding = offboarding;
  }

  // Reading a record is open to the onboarding HR, the employee's COMPANY_ADMIN (same company), OR the
  // SUPER_ADMIN (cross-company — the SA forms-viewer + Form-2 edit navigation, §2/§3.2). The service
  // (canAccessEmployee) scopes each. Overrides the class-level HR-only rule.
  @GetMapping("/{id}/record")
  @PreAuthorize("hasAnyRole('HR','COMPANY_ADMIN','SUPER_ADMIN')")
  public EmployeeRecordView record(
      @PathVariable String id,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    return review.getRecord(actor, id, request.getRemoteAddr());
  }

  /**
   * The accepted Offer Letter PDF (§3.2) — role-gated because it carries the SALARY. HR/COMPANY_ADMIN/
   * SUPER_ADMIN + canAccessEmployee only (the same audience as the record read); manager/accountant/lookup
   * never reach it (403). 404 until the offer is accepted. The employee reads their own via /me/onboarding/offer.
   */
  @GetMapping("/{id}/offer/pdf")
  @PreAuthorize("hasAnyRole('HR','COMPANY_ADMIN','SUPER_ADMIN')")
  public PresignedView offerPdf(
      @PathVariable String id,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    return offers.recordPdfUrl(actor, id, request.getRemoteAddr());
  }

  @GetMapping("/lookup/{employeeCode}")
  public EmployeeRecordView lookup(
      @PathVariable String employeeCode,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    return review.lookupByCode(actor, employeeCode, request.getRemoteAddr());
  }

  /** Explicit, audited reveal of the masked sensitive values (§6). */
  @PostMapping("/{id}/reveal")
  public RevealedSensitive reveal(
      @PathVariable String id,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    return review.reveal(actor, id, request.getRemoteAddr());
  }

  @PatchMapping("/{id}/forms/{form}")
  public EmployeeRecordView reviewForm(
      @PathVariable String id,
      @PathVariable String form,
      @Valid @RequestBody ReviewRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    return review.reviewForm(actor, id, form, body, request.getRemoteAddr());
  }

  @PatchMapping("/{id}/documents/{documentId}")
  public EmployeeRecordView reviewDocument(
      @PathVariable String id,
      @PathVariable String documentId,
      @Valid @RequestBody ReviewRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    return review.reviewDocument(actor, id, documentId, body, request.getRemoteAddr());
  }

  /**
   * HR APPROVES a verified employee (§3.3) — mints the ID; the team is the employee's onboarding-HR's team
   * (resolved server-side, not chosen), whose manager is notified.
   */
  @PostMapping("/{id}/approve")
  public DecisionResult approve(
      @PathVariable String id,
      @Valid @RequestBody(required = false) ApproveRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    DecisionResult result =
        review.approve(actor, id, body == null ? new ApproveRequest(null) : body, request.getRemoteAddr());
    // Post-commit + best-effort: push the team's manager + regenerate the PDFs with the minted ID. A failure
    // here must never undo the approval that already committed above.
    review.pushApprovalToManager(id);
    review.regeneratePdfsQuietly(id);
    return result;
  }

  /**
   * HR deactivates an offboarded employee's account (§3.6) — disables BOTH sign-in doors. Own case scope;
   * allowed only once the employee is OFFBOARDED; 409 if already deactivated. One-way (no reactivate).
   */
  @PostMapping("/{id}/deactivate")
  public void deactivate(
      @PathVariable String id,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    offboarding.deactivate(actor, id, request.getRemoteAddr());
  }

  /** HR terminally REJECTS a verified application (§3.3). */
  @PostMapping("/{id}/reject")
  public DecisionResult reject(
      @PathVariable String id,
      @Valid @RequestBody RejectRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    return review.reject(actor, id, body, request.getRemoteAddr());
  }

  /**
   * Assign (or re-issue) an APPROVED employee internal credentials (§8, Stage 5): a mailbox address +
   * password, emailed to their personal address. Allowed for the employee's ONBOARDING HR OR a
   * COMPANY_ADMIN of the same company (§6) — the service enforces that scope via canAccessEmployee.
   * Overrides the class-level HR-only rule.
   */
  @PostMapping("/{id}/credentials")
  @PreAuthorize("hasAnyRole('HR','COMPANY_ADMIN')")
  @ResponseStatus(HttpStatus.CREATED)
  public AssignCredentialsResult assignCredentials(
      @PathVariable String id,
      @Valid @RequestBody AssignCredentialsRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    return credentials.assign(actor, id, body, request.getRemoteAddr());
  }
}
