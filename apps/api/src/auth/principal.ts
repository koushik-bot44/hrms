import { randomInt } from 'node:crypto';
import type { Session, UserRole } from '@ihrms/shared';

/**
 * The authenticated caller, reconstructed from the access-token claims and attached to
 * `req.user`. Staff (USER) and onboarded subjects (EMPLOYEE) are different principals
 * with different scopes (§6).
 */
export interface UserPrincipal {
  type: 'USER';
  userId: string;
  email: string;
  name: string;
  role: UserRole;
  companyId: string | null; // null only for SUPER_ADMIN
  teamId: string | null;
}

export interface EmployeePrincipal {
  type: 'EMPLOYEE';
  employeeId: string;
  employeeCode: string;
  email: string;
  companyId: string;
}

export type Principal = UserPrincipal | EmployeePrincipal;

/** Minimal staff row needed to build a principal. */
export interface UserRow {
  id: string;
  email: string;
  name: string;
  role: UserRole;
  companyId: string | null;
  teamId: string | null;
}

/** Minimal employee row needed to build a principal. */
export interface EmployeeRow {
  id: string;
  employeeCode: string;
  email: string;
  companyId: string;
}

export function toUserPrincipal(user: UserRow): UserPrincipal {
  return {
    type: 'USER',
    userId: user.id,
    email: user.email,
    name: user.name,
    role: user.role,
    companyId: user.companyId,
    teamId: user.teamId,
  };
}

export function toEmployeePrincipal(employee: EmployeeRow): EmployeePrincipal {
  return {
    type: 'EMPLOYEE',
    employeeId: employee.id,
    employeeCode: employee.employeeCode,
    email: employee.email,
    companyId: employee.companyId,
  };
}

/** The public session shape sent to the web (matches `SessionSchema` in @ihrms/shared). */
export function toSession(p: Principal): Session {
  if (p.type === 'USER') {
    return {
      type: 'USER',
      userId: p.userId,
      email: p.email,
      name: p.name,
      role: p.role,
      companyId: p.companyId,
      teamId: p.teamId,
    };
  }
  return {
    type: 'EMPLOYEE',
    employeeId: p.employeeId,
    employeeCode: p.employeeCode,
    email: p.email,
    companyId: p.companyId,
  };
}

// ---------------------------------------------------------------------------
// JWT claims
// ---------------------------------------------------------------------------

export type TokenActor = 'USER' | 'EMPLOYEE';

export interface AccessClaims {
  sub: string;
  typ: 'access';
  actor: TokenActor;
  // staff
  role?: UserRole;
  companyId?: string | null;
  teamId?: string | null;
  name?: string;
  // employee
  employeeCode?: string;
  email: string;
}

export interface RefreshClaims {
  sub: string;
  typ: 'refresh';
  actor: TokenActor;
}

export function accessClaims(p: Principal): AccessClaims {
  if (p.type === 'USER') {
    return {
      sub: p.userId,
      typ: 'access',
      actor: 'USER',
      role: p.role,
      companyId: p.companyId,
      teamId: p.teamId,
      name: p.name,
      email: p.email,
    };
  }
  return {
    sub: p.employeeId,
    typ: 'access',
    actor: 'EMPLOYEE',
    employeeCode: p.employeeCode,
    companyId: p.companyId,
    email: p.email,
  };
}

export function refreshClaims(p: Principal): RefreshClaims {
  return {
    sub: p.type === 'USER' ? p.userId : p.employeeId,
    typ: 'refresh',
    actor: p.type,
  };
}

/** Rebuild a principal from verified access-token claims. */
export function principalFromAccessClaims(claims: AccessClaims): Principal | null {
  if (claims.typ !== 'access') return null;
  if (claims.actor === 'USER' && claims.role) {
    return {
      type: 'USER',
      userId: claims.sub,
      email: claims.email,
      name: claims.name ?? '',
      role: claims.role,
      companyId: claims.companyId ?? null,
      teamId: claims.teamId ?? null,
    };
  }
  if (claims.actor === 'EMPLOYEE' && claims.employeeCode && claims.companyId) {
    return {
      type: 'EMPLOYEE',
      employeeId: claims.sub,
      employeeCode: claims.employeeCode,
      email: claims.email,
      companyId: claims.companyId,
    };
  }
  return null;
}

/** A cryptographically-random 6-digit OTP, zero-padded. */
export function generateOtp(): string {
  return String(randomInt(0, 1_000_000)).padStart(6, '0');
}
