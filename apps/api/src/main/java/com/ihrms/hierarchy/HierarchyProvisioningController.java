package com.ihrms.hierarchy;

import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.hierarchy.dto.HierarchyDtos.HierarchyStatus;
import com.ihrms.hierarchy.dto.HierarchyDtos.ProvisionHierarchyRequest;
import com.ihrms.hierarchy.dto.HierarchyDtos.ProvisionHierarchyResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** SUPER_ADMIN provisions the single Hierarchy user (ARCHITECTURE.md §2). Read status + create only. */
@RestController
@RequestMapping("/provisioning/hierarchy")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class HierarchyProvisioningController {

  private final HierarchyService hierarchy;

  public HierarchyProvisioningController(HierarchyService hierarchy) {
    this.hierarchy = hierarchy;
  }

  @GetMapping
  public HierarchyStatus status() {
    return hierarchy.status();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ProvisionHierarchyResult provision(
      @Valid @RequestBody ProvisionHierarchyRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    return hierarchy.provision(actor, body, request.getRemoteAddr());
  }

  /** Remove the current Hierarchy so a replacement can be provisioned (§2). Idempotent. */
  @DeleteMapping
  public HierarchyStatus remove(
      @AuthenticationPrincipal IhrmsPrincipal.User actor, HttpServletRequest request) {
    return hierarchy.remove(actor, request.getRemoteAddr());
  }
}
