package com.ihrms.review;

import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.review.dto.ReviewDtos.EmployeeRecordView;
import com.ihrms.review.dto.ReviewDtos.RevealedSensitive;
import com.ihrms.review.dto.ReviewDtos.ReviewRequest;
import com.ihrms.review.dto.ReviewDtos.RouteToManagerRequest;
import com.ihrms.review.dto.ReviewDtos.RouteToManagerResult;
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

  public ReviewController(ReviewService review) {
    this.review = review;
  }

  @GetMapping("/{id}/record")
  public EmployeeRecordView record(
      @PathVariable String id,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    return review.getRecord(actor, id, request.getRemoteAddr());
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

  @PostMapping("/{id}/route-to-manager")
  @ResponseStatus(HttpStatus.CREATED)
  public RouteToManagerResult routeToManager(
      @PathVariable String id,
      @Valid @RequestBody(required = false) RouteToManagerRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    return review.routeToManager(actor, id, body, request.getRemoteAddr());
  }
}
