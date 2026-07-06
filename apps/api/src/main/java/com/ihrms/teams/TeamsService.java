package com.ihrms.teams;

import com.ihrms.audit.AuditActor;
import com.ihrms.audit.AuditService;
import com.ihrms.auth.AccountEmails;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.auth.MailService;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.Team;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.ApprovalRequestRepository;
import com.ihrms.domain.repository.TeamRepository;
import com.ihrms.domain.repository.UserRepository;
import com.ihrms.teams.dto.TeamDtos.AssignMemberRequest;
import com.ihrms.teams.dto.TeamDtos.AssignMemberResult;
import com.ihrms.teams.dto.TeamDtos.CreateTeamRequest;
import com.ihrms.teams.dto.TeamDtos.TeamDetailView;
import com.ihrms.teams.dto.TeamDtos.TeamMemberView;
import com.ihrms.teams.dto.TeamDtos.TeamSummaryView;
import com.ihrms.teams.dto.TeamDtos.UpdateTeamRequest;
import java.util.List;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Team management (ARCHITECTURE.md §2/§3.1). COMPANY_ADMIN-only; EVERY query is filtered by the
 * admin's companyId — no cross-tenant access. The single nullable hrUserId/managerUserId columns
 * structurally enforce exactly one HR + one Manager; a replaced holder is detached so the slot
 * keeps exactly one person.
 */
@Service
public class TeamsService {

  private final TeamRepository teams;
  private final UserRepository users;
  private final ApprovalRequestRepository approvals;
  private final AuditService audit;
  private final MailService mail;
  private final PasswordEncoder encoder;
  private final AccountEmails accountEmails;
  private final Environment env;

  public TeamsService(
      TeamRepository teams,
      UserRepository users,
      ApprovalRequestRepository approvals,
      AuditService audit,
      MailService mail,
      PasswordEncoder encoder,
      AccountEmails accountEmails,
      Environment env) {
    this.teams = teams;
    this.users = users;
    this.approvals = approvals;
    this.audit = audit;
    this.mail = mail;
    this.encoder = encoder;
    this.accountEmails = accountEmails;
    this.env = env;
  }

  public TeamDetailView create(
      String companyId, CreateTeamRequest input, IhrmsPrincipal.User actor, String ip) {
    Team team = new Team();
    team.setName(input.name().trim());
    team.setCompanyId(companyId);
    teams.save(team);
    audit(actor, companyId, "TEAM_CREATED", team.getId(), Map.of("name", team.getName()), ip);
    return detail(team);
  }

  public List<TeamSummaryView> list(String companyId) {
    return teams.findByCompanyIdOrderByCreatedAtDesc(companyId).stream().map(this::summary).toList();
  }

  public TeamDetailView getDetail(String companyId, String id) {
    return detail(requireTeam(id, companyId));
  }

  public TeamDetailView update(
      String companyId, String id, UpdateTeamRequest input, IhrmsPrincipal.User actor, String ip) {
    Team team = requireTeam(id, companyId);
    team.setName(input.name().trim());
    teams.save(team);
    audit(actor, companyId, "TEAM_UPDATED", id, Map.of("name", team.getName()), ip);
    return detail(team);
  }

  @Transactional
  public void remove(String companyId, String id, IhrmsPrincipal.User actor, String ip) {
    Team team = requireTeam(id, companyId);
    if (approvals.countByTeamId(id) > 0) {
      throw conflict("Team has approval history and cannot be deleted");
    }
    // Detach members (clears their teamId), then delete the team.
    for (User member : users.findByTeamIdOrderByNameAsc(id)) {
      member.setTeamId(null);
      users.save(member);
    }
    teams.delete(team);
    audit(actor, companyId, "TEAM_DELETED", id, Map.of(), ip);
  }

  public List<TeamMemberView> assignableUsers(String companyId, String role) {
    UserRole parsed = parseTeamRole(role);
    return users
        .findByCompanyIdAndRoleAndTeamIdIsNullOrderByNameAsc(companyId, parsed)
        .stream()
        .map(TeamsService::memberView)
        .toList();
  }

  /** Assign the HR or Manager slot by selecting an existing user or creating a new one. */
  @Transactional
  public AssignMemberResult assign(
      String companyId,
      String id,
      UserRole role,
      AssignMemberRequest input,
      IhrmsPrincipal.User actor,
      String ip) {
    Team team = requireTeam(id, companyId);

    boolean isHr = role == UserRole.HR;
    String currentUserId = isHr ? team.getHrUserId() : team.getManagerUserId();
    String otherUserId = isHr ? team.getManagerUserId() : team.getHrUserId();

    String userId;
    String devPassword = null;

    if (input.userId() != null && !input.userId().isBlank()) {
      User user =
          users
              .findByIdAndCompanyId(input.userId(), companyId)
              .orElseThrow(() -> notFound("User not found in your company"));
      if (user.getRole() != role) {
        throw badRequest("Selected user is not " + role);
      }
      if (user.getTeamId() != null && !user.getTeamId().equals(id)) {
        throw conflict("User is already assigned to another team");
      }
      userId = user.getId();
    } else {
      if (isBlank(input.name()) || isBlank(input.email())) {
        throw badRequest("Provide a userId or a name and email");
      }
      String email = input.email().trim().toLowerCase();
      accountEmails.assertAvailableForStaff(email); // unique across staff + employees (§6)
      // New staff sign in with email + password (§6): an initial password (min 8) is required.
      String password = input.password();
      if (isBlank(password) || password.trim().length() < 8) {
        throw badRequest("An initial password of at least 8 characters is required");
      }
      User created = new User();
      created.setName(input.name().trim());
      created.setEmail(email);
      created.setRole(role);
      created.setCompanyId(companyId);
      created.setTeamId(id);
      created.setPasswordHash(encoder.encode(password));
      created.setStatus("ACTIVE");
      try {
        users.saveAndFlush(created);
      } catch (DataIntegrityViolationException e) {
        throw conflict("Email \"" + email + "\" is already in use");
      }
      userId = created.getId();
      mail.sendStaffInvite(email, role.name(), password);
      devPassword = isProd() ? null : password;
    }

    if (otherUserId != null && otherUserId.equals(userId)) {
      throw conflict("This person already holds the other role on this team");
    }

    // Detach a replaced holder so the slot keeps exactly one person.
    if (currentUserId != null && !currentUserId.equals(userId)) {
      users
          .findById(currentUserId)
          .ifPresent(
              prev -> {
                prev.setTeamId(null);
                users.save(prev);
              });
    }
    User assignee = users.findById(userId).orElseThrow(() -> notFound("User not found"));
    assignee.setTeamId(id);
    assignee.setRole(role);
    users.save(assignee);
    if (isHr) {
      team.setHrUserId(userId);
    } else {
      team.setManagerUserId(userId);
    }
    teams.save(team);

    audit(
        actor,
        companyId,
        isHr ? "TEAM_HR_ASSIGNED" : "TEAM_MANAGER_ASSIGNED",
        id,
        Map.of("userId", userId, "role", role.name()),
        ip);

    return new AssignMemberResult(detail(team), devPassword);
  }

  // --- mapping --------------------------------------------------------------

  private TeamSummaryView summary(Team team) {
    return new TeamSummaryView(
        team.getId(),
        team.getName(),
        slotMember(team.getHrUserId()),
        slotMember(team.getManagerUserId()),
        users.countByTeamId(team.getId()),
        team.getCreatedAt().toString());
  }

  private TeamDetailView detail(Team team) {
    List<TeamMemberView> members =
        users.findByTeamIdOrderByNameAsc(team.getId()).stream().map(TeamsService::memberView).toList();
    return new TeamDetailView(
        team.getId(),
        team.getName(),
        slotMember(team.getHrUserId()),
        slotMember(team.getManagerUserId()),
        members.size(),
        team.getCreatedAt().toString(),
        members);
  }

  private TeamMemberView slotMember(String userId) {
    if (userId == null) {
      return null;
    }
    return users.findById(userId).map(TeamsService::memberView).orElse(null);
  }

  private static TeamMemberView memberView(User user) {
    return new TeamMemberView(
        user.getId(), user.getName(), user.getEmail(), user.getRole(), user.getStatus());
  }

  // --- helpers --------------------------------------------------------------

  private Team requireTeam(String id, String companyId) {
    return teams.findByIdAndCompanyId(id, companyId).orElseThrow(() -> notFound("Team not found"));
  }

  private UserRole parseTeamRole(String role) {
    if (UserRole.HR.name().equals(role)) {
      return UserRole.HR;
    }
    if (UserRole.MANAGER.name().equals(role)) {
      return UserRole.MANAGER;
    }
    throw badRequest("role must be HR or MANAGER");
  }

  private void audit(
      IhrmsPrincipal.User actor,
      String companyId,
      String action,
      String teamId,
      Map<String, Object> metadata,
      String ip) {
    // Partition under the TARGET company (SUPER_ADMIN actor has no company of its own).
    audit.record(new AuditActor("USER", actor.userId(), companyId), action, "Team", teamId, metadata, ip);
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  private boolean isProd() {
    return env.acceptsProfiles(Profiles.of("prod"));
  }

  private ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  private ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  private ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}
