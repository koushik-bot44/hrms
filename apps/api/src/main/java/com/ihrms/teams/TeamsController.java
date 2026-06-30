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

/** Team management (contract §3.4). COMPANY_ADMIN-only, scoped to the admin's company. */
@RestController
@RequestMapping("/teams")
@PreAuthorize("hasRole('COMPANY_ADMIN')")
public class TeamsController {

  private final TeamsService teams;

  public TeamsController(TeamsService teams) {
    this.teams = teams;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public TeamDetailView create(
      @Valid @RequestBody CreateTeamRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    return teams.create(body, actor, request.getRemoteAddr());
  }

  @GetMapping
  public List<TeamSummaryView> list(@AuthenticationPrincipal IhrmsPrincipal.User actor) {
    return teams.list(actor);
  }

  // Declared before '/{id}' so the literal path is not captured by the dynamic segment.
  @GetMapping("/assignable-users")
  public List<TeamMemberView> assignable(
      @RequestParam(name = "role", required = false) String role,
      @AuthenticationPrincipal IhrmsPrincipal.User actor) {
    return teams.assignableUsers(actor, role);
  }

  @GetMapping("/{id}")
  public TeamDetailView get(
      @PathVariable String id, @AuthenticationPrincipal IhrmsPrincipal.User actor) {
    return teams.getDetail(id, actor);
  }

  @PatchMapping("/{id}")
  public TeamDetailView update(
      @PathVariable String id,
      @Valid @RequestBody UpdateTeamRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    return teams.update(id, body, actor, request.getRemoteAddr());
  }

  @DeleteMapping("/{id}")
  public Map<String, Boolean> remove(
      @PathVariable String id,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    teams.remove(id, actor, request.getRemoteAddr());
    return Map.of("ok", true);
  }

  @PutMapping("/{id}/hr")
  public AssignMemberResult assignHr(
      @PathVariable String id,
      @RequestBody AssignMemberRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    return teams.assign(id, UserRole.HR, body, actor, request.getRemoteAddr());
  }

  @PutMapping("/{id}/manager")
  public AssignMemberResult assignManager(
      @PathVariable String id,
      @RequestBody AssignMemberRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    return teams.assign(id, UserRole.MANAGER, body, actor, request.getRemoteAddr());
  }
}
