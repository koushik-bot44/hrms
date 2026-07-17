package com.ihrms.hierarchy.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Hierarchy DTOs (ARCHITECTURE.md §2/§6). The Hierarchy is the single cross-platform, READ-ONLY,
 * AGGREGATES-ONLY overview role. Provisioning is SUPER_ADMIN-only (singleton), mirroring the Accounts
 * Admin: the mailbox local part forms {@code localPart@ihrms} (the platform domain) and IS the login
 * email. springdoc-visible so the web types regenerate. (No data-read DTOs yet — aggregate reads land
 * in a later stage under {@code /hierarchy/**}.)
 */
public final class HierarchyDtos {

  private HierarchyDtos() {}

  /** SUPER_ADMIN creates the single Hierarchy user with an initial staff password (§6). */
  public record ProvisionHierarchyRequest(
      @NotBlank(message = "Name is required") @Size(min = 2, max = 120, message = "Name is too long")
          String name,
      @NotBlank(message = "A mailbox name is required")
          @Size(max = 64, message = "Mailbox name is too long")
          String localPart,
      @NotBlank(message = "An initial password is required")
          @Size(min = 8, message = "Use at least 8 characters")
          String password) {}

  public record HierarchyView(
      String id, String email, String name, String status, String createdAt) {}

  /** {@code devPassword} omitted (not null) in production. */
  public record ProvisionHierarchyResult(
      HierarchyView hierarchy, @JsonInclude(JsonInclude.Include.NON_NULL) String devPassword) {}

  /** Whether the singleton Hierarchy exists yet — drives the SUPER_ADMIN provisioning UI. */
  public record HierarchyStatus(
      boolean exists, @JsonInclude(JsonInclude.Include.NON_NULL) HierarchyView hierarchy) {}
}
