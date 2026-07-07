package com.ihrms.teams.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ihrms.domain.enums.UserRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Team-management request/response DTOs — JSON shapes match api-contract.md §3.4 exactly. */
public final class TeamDtos {

  private TeamDtos() {}

  public record CreateTeamRequest(
      @NotBlank(message = "Name is required") @Size(min = 2, max = 120, message = "Name is too long")
          String name) {}

  public record UpdateTeamRequest(
      @NotBlank(message = "Name is required") @Size(min = 2, max = 120, message = "Name is too long")
          String name) {}

  /**
   * {@code AssignMemberInput = { userId } | { name, email, password } } — attach an existing company
   * user OR create a new one (with an initial sign-in password, min 8, for staff email+password login,
   * §6). The branch is resolved (and validated) in the service.
   */
  public record AssignMemberRequest(String userId, String name, String email, String password) {}

  public record TeamMemberView(String id, String name, String email, UserRole role, String status) {}

  /** {@code complete} = all three slots filled (§2); otherwise the team surfaces a "needs …" state. */
  public record TeamSummaryView(
      String id,
      String name,
      TeamMemberView hr,
      TeamMemberView manager,
      TeamMemberView accountant,
      boolean complete,
      long memberCount,
      String createdAt) {}

  /** {@code TeamDetail = TeamSummary & { members }}. */
  public record TeamDetailView(
      String id,
      String name,
      TeamMemberView hr,
      TeamMemberView manager,
      TeamMemberView accountant,
      boolean complete,
      long memberCount,
      String createdAt,
      List<TeamMemberView> members) {}

  /** {@code devPassword} present only when a new user was created in non-production. */
  public record AssignMemberResult(
      TeamDetailView team, @JsonInclude(JsonInclude.Include.NON_NULL) String devPassword) {}
}
