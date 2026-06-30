package com.ihrms.audit;

import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.auth.dto.SessionView;

/** Resolved actor context for an audit row (§7). */
public record AuditActor(String actorType, String actorId, String companyId) {

  public static final AuditActor SYSTEM = new AuditActor("SYSTEM", null, null);

  public static AuditActor from(IhrmsPrincipal principal) {
    if (principal == null) {
      return SYSTEM;
    }
    if (principal instanceof IhrmsPrincipal.User u) {
      return new AuditActor("USER", u.userId(), u.companyId());
    }
    IhrmsPrincipal.Employee e = (IhrmsPrincipal.Employee) principal;
    return new AuditActor("EMPLOYEE", e.employeeId(), e.companyId());
  }

  public static AuditActor from(SessionView session) {
    if (session instanceof SessionView.UserSession u) {
      return new AuditActor("USER", u.userId(), u.companyId());
    }
    SessionView.EmployeeSession e = (SessionView.EmployeeSession) session;
    return new AuditActor("EMPLOYEE", e.employeeId(), e.companyId());
  }
}
