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

  /** Reject creating a staff account with an email already used by an employee (personal OR mailbox). */
  public void assertAvailableForStaff(String email) {
    String e = norm(email);
    if (employees.findByEmail(e).isPresent() || employees.findByMailAddress(e).isPresent()) {
      throw conflict();
    }
  }

  /** Reject onboarding an employee with an email already used by a staff account or another mailbox. */
  public void assertAvailableForEmployee(String email) {
    String e = norm(email);
    if (users.findByEmail(e).isPresent() || employees.findByMailAddress(e).isPresent()) {
      throw conflict();
    }
  }

  /**
   * Reject assigning an employee mailbox address that collides with ANY login identifier — a staff email,
   * an employee's personal email, or another employee's mailbox (§8, Stage 5). {@code exceptEmployeeId}
   * lets an HR re-issue the SAME address to the same employee (their own current mailbox is not a clash).
   */
  public void assertMailAddressAvailable(String address, String exceptEmployeeId) {
    String a = norm(address);
    boolean taken =
        users.findByEmail(a).isPresent()
            || employees.findByEmail(a).isPresent()
            || employees
                .findByMailAddress(a)
                .filter(e -> !e.getId().equals(exceptEmployeeId))
                .isPresent();
    if (taken) {
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
