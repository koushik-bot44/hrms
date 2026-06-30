package com.ihrms.onboarding;

import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.onboarding.dto.OnboardingDtos.DocumentUploadRequest;
import com.ihrms.onboarding.dto.OnboardingDtos.DocumentView;
import com.ihrms.onboarding.dto.OnboardingDtos.OnboardingDashboard;
import com.ihrms.onboarding.dto.OnboardingDtos.PresignedUpload;
import com.ihrms.onboarding.dto.OnboardingDtos.PresignedView;
import com.ihrms.onboarding.dto.OnboardingDtos.ProfileSectionView;
import com.ihrms.onboarding.dto.OnboardingDtos.SaveSectionRequest;
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

/** Employee self-service onboarding (contract §3.6). EMPLOYEE-only; everything is own-record. */
@RestController
@RequestMapping("/me/onboarding")
@PreAuthorize("hasRole('EMPLOYEE')")
public class OnboardingController {

  private final OnboardingService onboarding;

  public OnboardingController(OnboardingService onboarding) {
    this.onboarding = onboarding;
  }

  @GetMapping
  public OnboardingDashboard dashboard(@AuthenticationPrincipal IhrmsPrincipal.Employee emp) {
    return onboarding.dashboard(emp);
  }

  @PutMapping("/sections/{key}")
  public ProfileSectionView saveSection(
      @PathVariable String key,
      @Valid @RequestBody SaveSectionRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.Employee emp,
      HttpServletRequest request) {
    return onboarding.saveSection(emp, key, body, request.getRemoteAddr());
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

  @PostMapping("/submit")
  @ResponseStatus(HttpStatus.CREATED)
  public OnboardingDashboard submit(
      @AuthenticationPrincipal IhrmsPrincipal.Employee emp, HttpServletRequest request) {
    return onboarding.submit(emp, request.getRemoteAddr());
  }
}
