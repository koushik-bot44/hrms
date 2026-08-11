import { describe, it, expect } from 'vitest';
import { UserRole, type Session } from '../contract';
import { homePathForSession } from './routes';

const PLATFORM = new Set<UserRole>([UserRole.SUPER_ADMIN, UserRole.ACCOUNTS_ADMIN, UserRole.HIERARCHY]);

function user(role: UserRole): Session {
  const platform = PLATFORM.has(role);
  return {
    type: 'USER',
    userId: 'u1',
    email: 'x@acme.test',
    name: 'X',
    role,
    companyId: platform ? null : 'c1',
    teamId: null,
    companySlug: platform ? null : 'acme',
  };
}

function employee(mailAddress: string | null): Session {
  return {
    type: 'EMPLOYEE',
    employeeId: 'e1',
    employeeCode: 'ACME-EMP-000001',
    email: 'p@ext.test',
    companyId: 'c1',
    name: 'Emp',
    mailAddress,
    authMethod: 'PASSWORD',
    companySlug: 'acme',
  };
}

// §6 two-door consolidation: post-login routing is UNCHANGED — the slug appears only AFTER sign-in. This locks
// that platform roles land on their TOP-LEVEL homes and company-scoped users on their SLUGGED homes.
describe('homePathForSession', () => {
  it('routes platform roles to their top-level homes', () => {
    expect(homePathForSession(user(UserRole.SUPER_ADMIN))).toBe('/super-admin');
    expect(homePathForSession(user(UserRole.ACCOUNTS_ADMIN))).toBe('/accounts');
    expect(homePathForSession(user(UserRole.HIERARCHY))).toBe('/hierarchy');
  });

  it('routes company staff to their slugged homes', () => {
    expect(homePathForSession(user(UserRole.HR))).toBe('/acme/hr');
    expect(homePathForSession(user(UserRole.MANAGER))).toBe('/acme/manager');
    expect(homePathForSession(user(UserRole.COMPANY_ADMIN))).toBe('/acme/company-admin');
    expect(homePathForSession(user(UserRole.ACCOUNTANT))).toBe('/acme/accountant');
  });

  it('routes an employee to workspace (credentialed) or onboarding (no mailbox) under the slug', () => {
    expect(homePathForSession(employee('arjun@acme'))).toBe('/acme/workspace');
    expect(homePathForSession(employee(null))).toBe('/acme/employee');
  });
});
