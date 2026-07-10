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

  public static SessionView toSession(IhrmsPrincipal principal) {
    if (principal instanceof IhrmsPrincipal.User u) {
      return new SessionView.UserSession(
          "USER", u.userId(), u.email(), u.name(), u.role(), u.companyId(), u.teamId());
    }
    IhrmsPrincipal.Employee e = (IhrmsPrincipal.Employee) principal;
    return new SessionView.EmployeeSession(
        "EMPLOYEE", e.employeeId(), e.employeeCode(), e.email(), e.companyId(), e.name(), e.mailAddress());
  }

  /** A cryptographically-random 6-digit, zero-padded OTP. */
  public static String generateOtp() {
    return String.format("%06d", RANDOM.nextInt(1_000_000));
  }
}
