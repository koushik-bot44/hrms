package com.ihrms.auth;

import com.ihrms.domain.enums.UserRole;

/**
 * The authenticated caller, reconstructed from the access-token claims and stored as the
 * Spring Security principal. Staff (USER) and onboarded subjects (EMPLOYEE) are different
 * principals with different scopes (§6).
 */
public sealed interface IhrmsPrincipal permits IhrmsPrincipal.User, IhrmsPrincipal.Employee {

  String id();

  String email();

  /** Staff/operator principal. {@code companyId} is null only for SUPER_ADMIN. */
  record User(String userId, String email, String name, UserRole role, String companyId, String teamId)
      implements IhrmsPrincipal {
    @Override
    public String id() {
      return userId;
    }
  }

  /** Onboarded-subject principal (own record only). */
  record Employee(String employeeId, String employeeCode, String email, String companyId)
      implements IhrmsPrincipal {
    @Override
    public String id() {
      return employeeId;
    }
  }
}
