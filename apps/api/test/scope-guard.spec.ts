import { ForbiddenException, type ExecutionContext, UnauthorizedException } from '@nestjs/common';
import { Reflector } from '@nestjs/core';
import { UserRole } from '@ihrms/shared';
import { describe, expect, it } from 'vitest';
import { ScopeGuard } from '../src/auth/guards/scope.guard';
import { EmployeeOnly, Roles } from '../src/auth/decorators/scope.decorator';
import { Public } from '../src/auth/decorators/public.decorator';
import type { Principal } from '../src/auth/principal';

/**
 * Each role against an allowed and a forbidden route, enforced through the real
 * @Scope/@Public metadata + ScopeGuard. No DB / Nest DI — pure guard decisions.
 */
class Routes {
  @Roles(UserRole.SUPER_ADMIN) superOnly(): void {}
  @Roles(UserRole.HR) hrOnly(): void {}
  @Roles(UserRole.MANAGER) managerOnly(): void {}
  @Roles(UserRole.COMPANY_ADMIN) companyAdminOnly(): void {}
  @EmployeeOnly() employeeArea(): void {}
  @Roles(UserRole.SUPER_ADMIN, UserRole.COMPANY_ADMIN, UserRole.HR, UserRole.MANAGER) staffArea(): void {}
  @Public() publicRoute(): void {}
  authedAny(): void {}
}

const guard = new ScopeGuard(new Reflector());
const routes = new Routes();

function ctx(handler: () => void, user?: Principal): ExecutionContext {
  return {
    getHandler: () => handler,
    getClass: () => Routes,
    switchToHttp: () => ({ getRequest: () => ({ user }) }),
  } as unknown as ExecutionContext;
}

const staff = (role: UserRole): Principal => ({
  type: 'USER',
  userId: `u-${role}`,
  email: `${role}@t.local`,
  name: role,
  role,
  companyId: role === UserRole.SUPER_ADMIN ? null : 'company-a',
  teamId: 'team-a',
});

const employee: Principal = {
  type: 'EMPLOYEE',
  employeeId: 'emp-1',
  employeeCode: 'ZQAA-EMP-000001',
  email: 'emp@t.local',
  companyId: 'company-a',
};

describe('ScopeGuard role/actor matrix (§6)', () => {
  it('SUPER_ADMIN: allowed on super-only, forbidden on employee-only', () => {
    expect(guard.canActivate(ctx(routes.superOnly, staff(UserRole.SUPER_ADMIN)))).toBe(true);
    expect(() => guard.canActivate(ctx(routes.employeeArea, staff(UserRole.SUPER_ADMIN)))).toThrow(
      ForbiddenException,
    );
  });

  it('COMPANY_ADMIN: allowed on company-admin-only, forbidden on super-only', () => {
    expect(guard.canActivate(ctx(routes.companyAdminOnly, staff(UserRole.COMPANY_ADMIN)))).toBe(true);
    expect(() => guard.canActivate(ctx(routes.superOnly, staff(UserRole.COMPANY_ADMIN)))).toThrow(
      ForbiddenException,
    );
  });

  it('HR: allowed on hr-only, forbidden on manager-only', () => {
    expect(guard.canActivate(ctx(routes.hrOnly, staff(UserRole.HR)))).toBe(true);
    expect(() => guard.canActivate(ctx(routes.managerOnly, staff(UserRole.HR)))).toThrow(
      ForbiddenException,
    );
  });

  it('MANAGER: allowed on manager-only, forbidden on hr-only', () => {
    expect(guard.canActivate(ctx(routes.managerOnly, staff(UserRole.MANAGER)))).toBe(true);
    expect(() => guard.canActivate(ctx(routes.hrOnly, staff(UserRole.MANAGER)))).toThrow(
      ForbiddenException,
    );
  });

  it('EMPLOYEE: allowed on employee-only, forbidden on staff area', () => {
    expect(guard.canActivate(ctx(routes.employeeArea, employee))).toBe(true);
    expect(() => guard.canActivate(ctx(routes.staffArea, employee))).toThrow(ForbiddenException);
  });

  it('staff cannot enter an employee-only route', () => {
    expect(() => guard.canActivate(ctx(routes.employeeArea, staff(UserRole.HR)))).toThrow(
      ForbiddenException,
    );
  });

  it('@Public needs no principal; undecorated routes require authentication', () => {
    expect(guard.canActivate(ctx(routes.publicRoute, undefined))).toBe(true);
    expect(guard.canActivate(ctx(routes.authedAny, staff(UserRole.HR)))).toBe(true);
    expect(() => guard.canActivate(ctx(routes.authedAny, undefined))).toThrow(UnauthorizedException);
  });
});
