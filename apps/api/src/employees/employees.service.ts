import { randomUUID } from 'node:crypto';
import { ConflictException, ForbiddenException, Injectable } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import {
  EMPLOYEE_SEQ_MAX,
  formatEmployeeCode,
  type EmployeeStatus,
  type EmployeeSummary,
  type OnboardEmployeeInput,
  type OnboardEmployeeResult,
} from '@ihrms/shared';
import type { Env } from '../config/env.validation';
import { PrismaService } from '../prisma/prisma.service';
import { AuditService } from '../audit/audit.service';
import { MailService } from '../auth/mail.service';
import type { UserPrincipal } from '../auth/principal';

const EMPLOYEE_SELECT = {
  id: true,
  employeeCode: true,
  email: true,
  status: true,
  createdAt: true,
} as const;

interface EmployeeRow {
  id: string;
  employeeCode: string;
  email: string;
  status: string;
  createdAt: Date;
}

function toSummary(employee: EmployeeRow): EmployeeSummary {
  return {
    id: employee.id,
    employeeCode: employee.employeeCode,
    email: employee.email,
    status: employee.status as EmployeeStatus,
    createdAt: employee.createdAt.toISOString(),
  };
}

/**
 * Employee onboarding (§3.2/§5). HR-only (controller guard); scoped to the HR's company.
 * The employee ID is allocated from an atomic per-company sequence so concurrent onboards
 * never collide.
 */
@Injectable()
export class EmployeesService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly audit: AuditService,
    private readonly mail: MailService,
    private readonly config: ConfigService<Env, true>,
  ) {}

  async onboard(
    input: OnboardEmployeeInput,
    actor: UserPrincipal,
    ip?: string,
  ): Promise<OnboardEmployeeResult> {
    const companyId = this.companyOf(actor);
    const company = await this.prisma.guarded.company.findUnique({
      where: { id: companyId },
      select: { code: true },
    });
    if (!company) {
      throw new ForbiddenException('No company in scope');
    }

    const sequence = await this.allocateSequence(companyId);
    if (sequence > EMPLOYEE_SEQ_MAX) {
      throw new ConflictException('Employee ID sequence exhausted for this company');
    }
    const employeeCode = formatEmployeeCode({ companyCode: company.code, sequence });

    const employee = await this.prisma.guarded.employee.create({
      data: {
        employeeCode,
        email: input.email,
        companyId,
        onboardingHrId: actor.userId,
        status: 'INVITED',
      },
      select: EMPLOYEE_SELECT,
    });

    const loginUrl = `${this.config.get('WEB_APP_URL', { infer: true }).replace(/\/+$/, '')}/login`;
    await this.mail.sendEmployeeOnboarding(employee.email, employee.employeeCode, loginUrl);

    await this.audit.record({
      actorType: 'USER',
      actorId: actor.userId,
      companyId,
      action: 'EMPLOYEE_ONBOARDED',
      targetType: 'Employee',
      targetId: employee.id,
      metadata: { employeeCode: employee.employeeCode, email: employee.email },
      ipAddress: ip ?? null,
    });

    return { employee: toSummary(employee), loginUrl };
  }

  /** Employees this HR onboarded, within their company (§6). */
  async listMine(actor: UserPrincipal): Promise<EmployeeSummary[]> {
    const companyId = this.companyOf(actor);
    const rows = await this.prisma.guarded.employee.findMany({
      where: { companyId, onboardingHrId: actor.userId },
      orderBy: { createdAt: 'desc' },
      select: EMPLOYEE_SELECT,
    });
    return rows.map(toSummary);
  }

  /**
   * Atomically allocate the next per-company sequence (§5). A single
   * `INSERT … ON CONFLICT … DO UPDATE SET lastSeq = lastSeq + 1 RETURNING` is
   * concurrency-safe and gap-tolerant — parallel onboards get distinct numbers.
   */
  private async allocateSequence(companyId: string): Promise<number> {
    const id = randomUUID();
    const rows = await this.prisma.guarded.$queryRaw<Array<{ lastSeq: number }>>`
      INSERT INTO "employee_code_sequences" ("id", "companyId", "lastSeq", "createdAt", "updatedAt")
      VALUES (${id}, ${companyId}, 1, now(), now())
      ON CONFLICT ("companyId") DO UPDATE
        SET "lastSeq" = "employee_code_sequences"."lastSeq" + 1, "updatedAt" = now()
      RETURNING "lastSeq";
    `;
    return Number(rows[0].lastSeq);
  }

  private companyOf(actor: UserPrincipal): string {
    if (!actor.companyId) {
      throw new ForbiddenException('No company in scope');
    }
    return actor.companyId;
  }
}
