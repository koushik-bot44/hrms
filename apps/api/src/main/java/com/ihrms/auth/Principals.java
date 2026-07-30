package com.ihrms.auth;

import com.ihrms.auth.dto.SessionView;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.User;
import java.security.SecureRandom;

/** Maps entities <-> principals <-> the public session, and generates OTPs. */
public final class Principals {

  private static final SecureRandom RANDOM = new SecureRandom();

  private Principals() {}

  public static IhrmsPrincipal.User of(User user) {
    return new IhrmsPrincipal.User(
        user.getId(),
        user.getEmail(),
        user.getName(),
        user.getRole(),
        user.getCompanyId(),
        user.getTeamId());
  }

  public static IhrmsPrincipal.Employee of(Employee employee) {
    return new IhrmsPrincipal.Employee(
        employee.getId(),
        employee.getEmployeeCode(),
        employee.getEmail(),
        employee.getCompanyId(),
        employee.getFullName(),
        employee.getMailAddress());
  }

  /** The session with an unknown login method + unresolved slug (e.g. tests). */
  public static SessionView toSession(IhrmsPrincipal principal) {
    return toSession(principal, null, null);
  }

  /** The session tagging the door used, with an unresolved slug. */
  public static SessionView toSession(IhrmsPrincipal principal, String authMethod) {
    return toSession(principal, authMethod, null);
  }

  /**
   * The public session — tags the EMPLOYEE variant with which door was used (Stage 6 landing routing)
   * and carries the company URL slug (Stage 2 routing); {@code companySlug} is null for platform roles.
   */
  public static SessionView toSession(
      IhrmsPrincipal principal, String authMethod, String companySlug) {
    if (principal instanceof IhrmsPrincipal.User u) {
      return new SessionView.UserSession(
          "USER", u.userId(), u.email(), u.name(), u.role(), u.companyId(), u.teamId(), companySlug);
    }
    IhrmsPrincipal.Employee e = (IhrmsPrincipal.Employee) principal;
    return new SessionView.EmployeeSession(
        "EMPLOYEE",
        e.employeeId(),
        e.employeeCode(),
        e.email(),
        e.companyId(),
        e.name(),
        e.mailAddress(),
        authMethod,
        companySlug);
  }

  /** A cryptographically-random 6-digit, zero-padded OTP. */
  public static String generateOtp() {
    return String.format("%06d", RANDOM.nextInt(1_000_000));
  }
}
