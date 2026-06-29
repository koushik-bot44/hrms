import { ForbiddenException, Injectable, NotFoundException } from '@nestjs/common';
import { PrismaService } from '../prisma/prisma.service';
import type { Principal } from './principal';

/** The slice of an employee needed to decide access. */
export interface EmployeeScope {
  id: string;
  companyId: string;
  onboardingHrId: string;
  employeeCode: string;
}

/**
 * The ONE place hierarchy/tenancy checks live (§6). Handlers call these instead of
 * re-deriving scope; every company-scoped query filters by `companyId`.
 *
 * Matrix:
 *  - SUPER_ADMIN  → all companies
 *  - COMPANY_ADMIN→ own companyId
 *  - HR           → own onboarded employees within own company (onboardingHrId == self)
 *  - MANAGER      → employees onboarded by an HR on the manager's team, same company
 *  - EMPLOYEE     → own record only
 */
@Injectable()
export class AccessControlService {
  constructor(private readonly prisma: PrismaService) {}

  /** The companyId a principal is locked to, or null for SUPER_ADMIN (all companies). */
  tenantCompanyId(principal: Principal): string | null {
    if (principal.type === 'EMPLOYEE') {
      return principal.companyId;
    }
    if (principal.role === 'SUPER_ADMIN') {
      return null;
    }
    return principal.companyId;
  }

  /** Throw unless the principal may act within `companyId`. */
  assertCompany(principal: Principal, companyId: string): void {
    if (principal.type === 'USER' && principal.role === 'SUPER_ADMIN') {
      return;
    }
    if (this.tenantCompanyId(principal) !== companyId) {
      throw new ForbiddenException('Outside your company scope');
    }
  }

  /**
   * Loads an employee and asserts the principal may access it. Returns the scoped row,
   * or throws NotFound / Forbidden. The single entry point for employee-record access.
   */
  async assertCanAccessEmployee(principal: Principal, employeeId: string): Promise<EmployeeScope> {
    const employee = await this.prisma.guarded.employee.findUnique({
      where: { id: employeeId },
      select: { id: true, companyId: true, onboardingHrId: true, employeeCode: true },
    });
    if (!employee) {
      throw new NotFoundException('Employee not found');
    }
    if (!(await this.canAccessEmployee(principal, employee))) {
      throw new ForbiddenException('Outside your scope');
    }
    return employee;
  }

  /** Pure decision for employee-record access — also used directly by tests. */
  async canAccessEmployee(principal: Principal, employee: EmployeeScope): Promise<boolean> {
    if (principal.type === 'EMPLOYEE') {
      return employee.id === principal.employeeId;
    }

    switch (principal.role) {
      case 'SUPER_ADMIN':
        return true;
      case 'COMPANY_ADMIN':
        return employee.companyId === principal.companyId;
      case 'HR':
        return (
          employee.companyId === principal.companyId &&
          employee.onboardingHrId === principal.userId
        );
      case 'MANAGER': {
        if (employee.companyId !== principal.companyId) {
          return false;
        }
        // In scope iff the onboarding HR is the HR on a team this manager manages.
        const team = await this.prisma.guarded.team.findFirst({
          where: {
            companyId: principal.companyId ?? undefined,
            managerUserId: principal.userId,
            hrUserId: employee.onboardingHrId,
          },
          select: { id: true },
        });
        return Boolean(team);
      }
      default:
        return false;
    }
  }
}
