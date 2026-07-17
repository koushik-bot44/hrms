package com.ihrms.hierarchy;

import com.ihrms.audit.AuditActor;
import com.ihrms.audit.AuditService;
import com.ihrms.auth.AccountEmails;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.auth.MailService;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.UserRepository;
import com.ihrms.hierarchy.dto.HierarchyDtos.HierarchyStatus;
import com.ihrms.hierarchy.dto.HierarchyDtos.HierarchyView;
import com.ihrms.hierarchy.dto.HierarchyDtos.ProvisionHierarchyRequest;
import com.ihrms.hierarchy.dto.HierarchyDtos.ProvisionHierarchyResult;
import com.ihrms.mail.MailAddresses;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Provisions and inspects the single cross-platform HIERARCHY role (ARCHITECTURE.md §2/§6) — a
 * read-only, aggregates-only overview principal ({@code companyId = null}). Mirrors the Accounts Admin
 * provisioning exactly (SUPER_ADMIN-only singleton; {@code localPart@ihrms} address = login email; BCrypt
 * password; audited). This stage has NO data reads — aggregate endpoints land later under
 * {@code /hierarchy/**}. Nothing here (or anywhere) lets a Hierarchy mutate state or read a record.
 */
@Service
public class HierarchyService {

  private final UserRepository users;
  private final AccountEmails accountEmails;
  private final MailAddresses mailAddresses;
  private final PasswordEncoder encoder;
  private final MailService mail;
  private final AuditService audit;
  private final Environment env;

  public HierarchyService(
      UserRepository users,
      AccountEmails accountEmails,
      MailAddresses mailAddresses,
      PasswordEncoder encoder,
      MailService mail,
      AuditService audit,
      Environment env) {
    this.users = users;
    this.accountEmails = accountEmails;
    this.mailAddresses = mailAddresses;
    this.encoder = encoder;
    this.mail = mail;
    this.audit = audit;
    this.env = env;
  }

  /** Whether the single Hierarchy has been provisioned — drives the SUPER_ADMIN UI. */
  public HierarchyStatus status() {
    return users
        .findFirstByRoleOrderByCreatedAtAsc(UserRole.HIERARCHY)
        .map(u -> new HierarchyStatus(true, view(u)))
        .orElseGet(() -> new HierarchyStatus(false, null));
  }

  /** Create THE Hierarchy. Rejects a second one (singleton) and a taken email. Audited. */
  public ProvisionHierarchyResult provision(
      IhrmsPrincipal.User actor, ProvisionHierarchyRequest input, String ip) {
    if (users.existsByRole(UserRole.HIERARCHY)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "A Hierarchy user already exists (only one is allowed)");
    }
    // Platform-domain mailbox: localPart@ihrms, which IS the login email (§8) — same as the Accounts Admin.
    var address = mailAddresses.resolve(input.localPart(), MailAddresses.PLATFORM_DOMAIN);
    accountEmails.assertAvailableForStaff(address.email()); // unique across staff + employees (§6)

    User user = new User();
    user.setEmail(address.email());
    user.setMailLocalPart(address.localPart());
    user.setName(input.name().trim());
    user.setRole(UserRole.HIERARCHY);
    user.setCompanyId(null); // cross-platform, like SUPER_ADMIN / ACCOUNTS_ADMIN
    user.setPasswordHash(encoder.encode(input.password()));
    user.setStatus("ACTIVE");
    try {
      users.save(user);
    } catch (DataIntegrityViolationException e) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Email \"" + address.email() + "\" is already in use");
    }

    mail.sendStaffInvite(address.email(), "HIERARCHY", input.password());
    // Portal-level event (no companyId) — the Hierarchy belongs to no company.
    audit.record(
        new AuditActor("USER", actor.userId(), null),
        "HIERARCHY_PROVISIONED",
        "User",
        user.getId(),
        Map.of("email", address.email()),
        ip);

    return new ProvisionHierarchyResult(view(user), isProd() ? null : input.password());
  }

  /**
   * Remove the current Hierarchy so a new one can be provisioned (§2). Idempotent — a no-op if none
   * exists. Audited. Audit history is retained (rows reference a plain actorId, not an FK), and any
   * already-issued access token simply expires.
   */
  public HierarchyStatus remove(IhrmsPrincipal.User actor, String ip) {
    users
        .findFirstByRoleOrderByCreatedAtAsc(UserRole.HIERARCHY)
        .ifPresent(
            h -> {
              users.delete(h);
              audit.record(
                  new AuditActor("USER", actor.userId(), null),
                  "HIERARCHY_REMOVED",
                  "User",
                  h.getId(),
                  Map.of("email", h.getEmail()),
                  ip);
            });
    return new HierarchyStatus(false, null);
  }

  private HierarchyView view(User u) {
    return new HierarchyView(
        u.getId(), u.getEmail(), u.getName(), u.getStatus(),
        u.getCreatedAt() == null ? null : u.getCreatedAt().toString());
  }

  private boolean isProd() {
    return env.acceptsProfiles(Profiles.of("prod"));
  }
}
