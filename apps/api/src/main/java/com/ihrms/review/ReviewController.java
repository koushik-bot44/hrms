package com.ihrms.review;

import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.review.dto.ReviewDtos.EmployeeRecordView;
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
 * HR verification & routing workspace (contract §3.3/§3.4). HR-only (URL rule + @PreAuthorize);
 * every operation is scoped to the acting HR's own onboarded employees.
 */
@RestController
@RequestMapping("/employees")
@PreAuthorize("hasRole('HR')")
public class ReviewController {

  private final ReviewService review;

  public ReviewController(ReviewService review) {
    this.review = review;
  }

  @GetMapping("/{employeeCode}")
  public EmployeeRecordView record(
      @PathVariable String employeeCode,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    return review.getRecord(actor, employeeCode, request.getRemoteAddr());
  }

  @PatchMapping("/{employeeCode}/sections/{key}")
  public EmployeeRecordView reviewSection(
      @PathVariable String employeeCode,
      @PathVariable String key,
      @Valid @RequestBody ReviewRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    return review.reviewSection(actor, employeeCode, key, body, request.getRemoteAddr());
  }

  @PatchMapping("/{employeeCode}/documents/{documentId}")
  public EmployeeRecordView reviewDocument(
      @PathVariable String employeeCode,
      @PathVariable String documentId,
      @Valid @RequestBody ReviewRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    return review.reviewDocument(actor, employeeCode, documentId, body, request.getRemoteAddr());
  }

  @PostMapping("/{employeeCode}/route-to-manager")
  @ResponseStatus(HttpStatus.CREATED)
  public RouteToManagerResult routeToManager(
      @PathVariable String employeeCode,
      @Valid @RequestBody(required = false) RouteToManagerRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    return review.routeToManager(actor, employeeCode, body, request.getRemoteAddr());
  }
}
