package com.ihrms.auth;

import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Enforces that a login email is unique ACROSS both the User (staff) and Employee tables (§6). This
 * keeps the unified OTP resolver deterministic — an email resolves to exactly one account, never
 * both. Each table already enforces uniqueness within itself; this guards the cross-table case at
 * account creation.
 */
@Component
public class AccountEmails {

  private final UserRepository users;
  private final EmployeeRepository employees;

  public AccountEmails(UserRepository users, EmployeeRepository employees) {
    this.users = users;
    this.employees = employees;
  }

  /** Reject creating a staff account with an email already used by an employee. */
  public void assertAvailableForStaff(String email) {
    if (employees.findByEmail(norm(email)).isPresent()) {
      throw conflict();
    }
  }

  /** Reject onboarding an employee with an email already used by a staff account. */
  public void assertAvailableForEmployee(String email) {
    if (users.findByEmail(norm(email)).isPresent()) {
      throw conflict();
    }
  }

  private static String norm(String email) {
    return email == null ? null : email.trim().toLowerCase();
  }

  private static ResponseStatusException conflict() {
    return new ResponseStatusException(HttpStatus.CONFLICT, "This email is already in use");
  }
}
