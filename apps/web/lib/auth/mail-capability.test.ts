import { describe, it, expect } from 'vitest';
import { UserRole, type Session } from '../contract';
import { canUseMail } from './mail-capability';

function user(role: UserRole): Session {
  return {
    type: 'USER',
    userId: 'u1',
    email: 'x@acme.test',
    name: 'X',
    role,
    companyId: role === UserRole.HIERARCHY || role === UserRole.SUPER_ADMIN ? null : 'c1',
    teamId: null,
    companySlug: role === UserRole.HIERARCHY || role === UserRole.SUPER_ADMIN ? null : 'acme',
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

describe('canUseMail', () => {
  it('hides mail for HIERARCHY (no mail edge)', () => {
    expect(canUseMail(user(UserRole.HIERARCHY))).toBe(false);
  });

  it('shows mail for every other staff role', () => {
    for (const role of [
      UserRole.SUPER_ADMIN,
      UserRole.COMPANY_ADMIN,
      UserRole.HR,
      UserRole.MANAGER,
      UserRole.ACCOUNTANT,
      UserRole.ACCOUNTS_ADMIN,
    ]) {
      expect(canUseMail(user(role))).toBe(true);
    }
  });

  it('shows mail for a credentialed employee, not an OTP-only one', () => {
    expect(canUseMail(employee('arjun@acme'))).toBe(true);
    expect(canUseMail(employee(null))).toBe(false);
  });

  it('is false for no session', () => {
    expect(canUseMail(null)).toBe(false);
    expect(canUseMail(undefined)).toBe(false);
  });
});
