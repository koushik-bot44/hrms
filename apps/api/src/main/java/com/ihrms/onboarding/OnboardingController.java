package com.ihrms.onboarding;

import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.onboarding.dto.OfferDtos.AcceptOfferRequest;
import com.ihrms.onboarding.dto.OfferDtos.MyOfferView;
import com.ihrms.onboarding.dto.OnboardingDtos.DocumentReviseRequest;
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

/** Employee self-service onboarding — the four-form stepper (§3.2). EMPLOYEE-only; own-record. */
@RestController
@RequestMapping("/me/onboarding")
@PreAuthorize("hasRole('EMPLOYEE')")
public class OnboardingController {

  private final OnboardingService onboarding;
  private final OfferService offers;

  public OnboardingController(OnboardingService onboarding, OfferService offers) {
    this.onboarding = onboarding;
    this.offers = offers;
  }

  @GetMapping
  public OnboardingDashboard dashboard(@AuthenticationPrincipal IhrmsPrincipal.Employee emp) {
    return onboarding.dashboard(emp);
  }

  // --- Offer Letter (§3.2): the gate that opens onboarding ------------------

  /** The invited employee's offer screen (full text + status); null when there is no offer (ungated). */
  @GetMapping("/offer")
  public MyOfferView offer(@AuthenticationPrincipal IhrmsPrincipal.Employee emp) {
    return offers.myOffer(emp);
  }

  /** Accept the offer — consent + signature; unlocks the forms + stores the accepted PDF on the record. */
  @PostMapping("/offer/accept")
  public OnboardingDashboard acceptOffer(
      @RequestBody(required = false) AcceptOfferRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.Employee emp,
      HttpServletRequest request) {
    offers.accept(emp, body, request.getRemoteAddr());
    offers.notifyHrAfterAccept(emp.employeeId()); // post-commit, best-effort
    return onboarding.dashboard(emp);
  }

  @PutMapping("/form1")
  public Form1View saveForm1(
      @Valid @RequestBody Form1Request body,
      @AuthenticationPrincipal IhrmsPrincipal.Employee emp,
      HttpServletRequest request) {
    return onboarding.saveForm1(emp, body, request.getRemoteAddr());
  }

  // Form 2 is HR/SA-authored at onboard (§3.2) — there is no employee endpoint to fill it.

  @PutMapping("/form3")
  public List<Form3EntryView> saveForm3(
      @Valid @RequestBody Form3Request body,
      @AuthenticationPrincipal IhrmsPrincipal.Employee emp,
      HttpServletRequest request) {
    return onboarding.saveForm3(emp, body, request.getRemoteAddr());
  }

  @PostMapping("/documents")
  @ResponseStatus(HttpStatus.CREATED)
  public PresignedUpload requestUpload(
      @Valid @RequestBody DocumentUploadRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.Employee emp,
      HttpServletRequest request) {
    return onboarding.requestUpload(emp, body, request.getRemoteAddr());
  }

  @PostMapping("/documents/{id}/confirm")
  @ResponseStatus(HttpStatus.CREATED)
  public DocumentView confirm(
      @PathVariable String id,
      @AuthenticationPrincipal IhrmsPrincipal.Employee emp,
      HttpServletRequest request) {
    return onboarding.confirmUpload(emp, id, request.getRemoteAddr());
  }

  @GetMapping("/documents/{id}/url")
  public PresignedView viewUrl(
      @PathVariable String id,
      @AuthenticationPrincipal IhrmsPrincipal.Employee emp,
      HttpServletRequest request) {
    return onboarding.documentViewUrl(emp, id, request.getRemoteAddr());
  }

  /** Re-upload a document HR sent back for revision (§3.3); confirm finalises it. */
  @PostMapping("/documents/{id}/revise")
  @ResponseStatus(HttpStatus.CREATED)
  public PresignedUpload revise(
      @PathVariable String id,
      @Valid @RequestBody DocumentReviseRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.Employee emp,
      HttpServletRequest request) {
    return onboarding.reviseDocument(emp, id, body, request.getRemoteAddr());
  }

  @DeleteMapping("/documents/{id}")
  public OnboardingDashboard deleteDocument(
      @PathVariable String id,
      @AuthenticationPrincipal IhrmsPrincipal.Employee emp,
      HttpServletRequest request) {
    return onboarding.deleteDocument(emp, id, request.getRemoteAddr());
  }

  @PutMapping("/signature")
  public SignatureView saveSignature(
      @Valid @RequestBody SignatureRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.Employee emp,
      HttpServletRequest request) {
    return onboarding.saveSignature(emp, body, request.getRemoteAddr());
  }

  @GetMapping("/generated/{id}/url")
  public PresignedView generatedUrl(
      @PathVariable String id,
      @AuthenticationPrincipal IhrmsPrincipal.Employee emp,
      HttpServletRequest request) {
    return onboarding.generatedViewUrl(emp, id, request.getRemoteAddr());
  }

  @PostMapping("/submit")
  @ResponseStatus(HttpStatus.CREATED)
  public OnboardingDashboard submit(
      @AuthenticationPrincipal IhrmsPrincipal.Employee emp, HttpServletRequest request) {
    onboarding.submit(emp, request.getRemoteAddr());
    // Post-commit + best-effort: generate the PDFs, then return the dashboard reflecting them.
    onboarding.regeneratePdfsQuietly(emp);
    return onboarding.dashboard(emp);
  }

  /** Re-submit after fixing the items HR sent back (§3.3); regenerates the affected PDFs. */
  @PostMapping("/resubmit")
  @ResponseStatus(HttpStatus.CREATED)
  public OnboardingDashboard resubmit(
      @AuthenticationPrincipal IhrmsPrincipal.Employee emp, HttpServletRequest request) {
    onboarding.resubmit(emp, request.getRemoteAddr());
    // Same post-commit, best-effort PDF regeneration as the first submit.
    onboarding.regeneratePdfsQuietly(emp);
    return onboarding.dashboard(emp);
  }
}
