package com.ihrms.onboarding;

import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.onboarding.dto.OnboardingDtos.DocumentUploadRequest;
import com.ihrms.onboarding.dto.OnboardingDtos.DocumentView;
import com.ihrms.onboarding.dto.OnboardingDtos.Form1Request;
import com.ihrms.onboarding.dto.OnboardingDtos.Form1View;
import com.ihrms.onboarding.dto.OnboardingDtos.Form3EntryView;
import com.ihrms.onboarding.dto.OnboardingDtos.Form3Request;
import com.ihrms.onboarding.dto.OnboardingDtos.OnboardingDashboard;
import com.ihrms.onboarding.dto.OnboardingDtos.PresignedUpload;
import com.ihrms.onboarding.dto.OnboardingDtos.PresignedView;
import com.ihrms.onboarding.dto.OnboardingDtos.SignatureRequest;
import com.ihrms.onboarding.dto.OnboardingDtos.SignatureView;
import com.ihrms.review.dto.ReviewDtos.DecisionResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * HR entry for an EXISTING employee's record (§3.2, contract §3.5). HR-only (URL rule + @PreAuthorize),
 * scoped to the actor's own onboarded employees, EXISTING_EMPLOYEE-only, writable until approved. Mirrors
 * the employee's own {@code /me/onboarding} surface — same request/response shapes — with HR as the actor
 * and no offer gate. Approval keeps the entered ID and sends no email.
 */
@RestController
@RequestMapping("/employees/{id}/onboarding")
@PreAuthorize("hasRole('HR')")
public class HrOnboardingController {

  private final HrOnboardingService entry;

  public HrOnboardingController(HrOnboardingService entry) {
    this.entry = entry;
  }

  @GetMapping
  public OnboardingDashboard dashboard(
      @PathVariable String id, @AuthenticationPrincipal IhrmsPrincipal.User actor) {
    return entry.dashboard(actor, id);
  }

  @PutMapping("/form1")
  public Form1View saveForm1(
      @PathVariable String id,
      @Valid @RequestBody Form1Request body,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    return entry.saveForm1(actor, id, body, request.getRemoteAddr());
  }

  @PutMapping("/form3")
  public List<Form3EntryView> saveForm3(
      @PathVariable String id,
      @Valid @RequestBody Form3Request body,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    return entry.saveForm3(actor, id, body, request.getRemoteAddr());
  }

  @PutMapping("/signature")
  public SignatureView saveSignature(
      @PathVariable String id,
      @Valid @RequestBody SignatureRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    return entry.saveSignature(actor, id, body, request.getRemoteAddr());
  }

  @PostMapping("/documents")
  @ResponseStatus(HttpStatus.CREATED)
  public PresignedUpload requestUpload(
      @PathVariable String id,
      @Valid @RequestBody DocumentUploadRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    return entry.requestUpload(actor, id, body, request.getRemoteAddr());
  }

  @PostMapping("/documents/{documentId}/confirm")
  @ResponseStatus(HttpStatus.CREATED)
  public DocumentView confirmUpload(
      @PathVariable String id,
      @PathVariable String documentId,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    return entry.confirmUpload(actor, id, documentId, request.getRemoteAddr());
  }

  @DeleteMapping("/documents/{documentId}")
  public OnboardingDashboard deleteDocument(
      @PathVariable String id,
      @PathVariable String documentId,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    return entry.deleteDocument(actor, id, documentId, request.getRemoteAddr());
  }

  @GetMapping("/documents/{documentId}/url")
  public PresignedView documentViewUrl(
      @PathVariable String id,
      @PathVariable String documentId,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    return entry.documentViewUrl(actor, id, documentId, request.getRemoteAddr());
  }

  /**
   * HR approves the completed record (§3.2): sections/documents flip to VERIFIED, the entered employee ID is
   * kept as the code, the team's manager is notified — and no email is sent.
   */
  @PostMapping("/approve")
  public DecisionResult approve(
      @PathVariable String id,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    DecisionResult result = entry.approve(actor, id, request.getRemoteAddr());
    // Post-commit + best-effort: regenerate the PDFs with the kept ID stamped on them. A failure here must
    // never undo the approval that already committed above.
    entry.regeneratePdfsQuietly(id);
    return result;
  }
}
