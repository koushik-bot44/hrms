package com.ihrms.teams;

import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.teams.dto.TeamDtos.AssignMemberRequest;
import com.ihrms.teams.dto.TeamDtos.AssignMemberResult;
import com.ihrms.teams.dto.TeamDtos.CreateTeamRequest;
import com.ihrms.teams.dto.TeamDtos.TeamDetailView;
import com.ihrms.teams.dto.TeamDtos.TeamMemberView;
import com.ihrms.teams.dto.TeamDtos.TeamSummaryView;
import com.ihrms.teams.dto.TeamDtos.UpdateTeamRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * SUPER_ADMIN team management for ANY company (ARCHITECTURE.md §2). Delegates to the SAME
 * {@link TeamsService} used by COMPANY_ADMIN — the only difference is the companyId comes from the
 * path (a Super Admin can act cross-company) instead of the actor. The one-HR-one-Manager rule and
 * cross-company rejection are enforced in the service; actions are audited under the target company.
 */
@RestController
@RequestMapping("/companies/{companyId}/teams")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class CompanyTeamsController {

  private final TeamsService teams;

  public CompanyTeamsController(TeamsService teams) {
    this.teams = teams;
  }

  @GetMapping
  public List<TeamSummaryView> list(@PathVariable String companyId) {
    return teams.list(companyId);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public TeamDetailView create(
      @PathVariable String companyId,
      @Valid @RequestBody CreateTeamRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    return teams.create(companyId, body, actor, request.getRemoteAddr());
  }

  // Declared before '/{id}' so the literal path is not captured by the dynamic segment.
  @GetMapping("/assignable-users")
  public List<TeamMemberView> assignable(
      @PathVariable String companyId,
      @RequestParam(name = "role", required = false) String role) {
    return teams.assignableUsers(companyId, role);
  }

  @GetMapping("/{id}")
  public TeamDetailView get(@PathVariable String companyId, @PathVariable String id) {
    return teams.getDetail(companyId, id);
  }

  @PatchMapping("/{id}")
  public TeamDetailView update(
      @PathVariable String companyId,
      @PathVariable String id,
      @Valid @RequestBody UpdateTeamRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    return teams.update(companyId, id, body, actor, request.getRemoteAddr());
  }

  @DeleteMapping("/{id}")
  public Map<String, Boolean> remove(
      @PathVariable String companyId,
      @PathVariable String id,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    teams.remove(companyId, id, actor, request.getRemoteAddr());
    return Map.of("ok", true);
  }

  @PutMapping("/{id}/hr")
  public AssignMemberResult assignHr(
      @PathVariable String companyId,
      @PathVariable String id,
      @RequestBody AssignMemberRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    return teams.assign(companyId, id, UserRole.HR, body, actor, request.getRemoteAddr());
  }

  @PutMapping("/{id}/manager")
  public AssignMemberResult assignManager(
      @PathVariable String companyId,
      @PathVariable String id,
      @RequestBody AssignMemberRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    return teams.assign(companyId, id, UserRole.MANAGER, body, actor, request.getRemoteAddr());
  }

  @PutMapping("/{id}/accountant")
  public AssignMemberResult assignAccountant(
      @PathVariable String companyId,
      @PathVariable String id,
      @RequestBody AssignMemberRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    return teams.assign(companyId, id, UserRole.ACCOUNTANT, body, actor, request.getRemoteAddr());
  }
}
