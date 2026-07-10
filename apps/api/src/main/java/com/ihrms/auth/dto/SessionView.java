package com.ihrms.auth.dto;

import com.ihrms.domain.enums.UserRole;

/**
 * The public session (contract §3.2) — a discriminated union on {@code type}. Returned by
 * {@code /auth/me} and inside {@code AuthResult.session}. Jackson serializes the concrete
 * record's fields (nulls included for USER's companyId/teamId, matching the contract).
 */
public sealed interface SessionView permits SessionView.UserSession, SessionView.EmployeeSession {

  record UserSession(
      String type,
      String userId,
      String email,
      String name,
      UserRole role,
      String companyId,
      String teamId)
      implements SessionView {}

  record EmployeeSession(
      String type,
      String employeeId,
      String employeeCode,
      String email,
      String companyId,
      // Populated once the employee has internal credentials (§8, Stage 5); null before then.
      String name,
      String mailAddress,
      // Which sign-in door was used (Stage 6): PASSWORD -> portal, OTP -> onboarding. null when unknown
      // (e.g. /auth/me, which rebuilds from the access token, which does not carry it).
      String authMethod)
      implements SessionView {}
}
