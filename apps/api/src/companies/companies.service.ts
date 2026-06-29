import { randomBytes } from 'node:crypto';
import { ConflictException, Injectable, NotFoundException } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { Prisma, UserRole } from '@prisma/client';
import type {
  CompanyAdmin,
  CompanyDetail,
  CompanyStatus,
  CompanySummary,
  CreateCompanyInput,
  ProvisionCompanyAdminInput,
  ProvisionCompanyAdminResult,
  UpdateCompanyInput,
} from '@ihrms/shared';
import type { Env } from '../config/env.validation';
import { PrismaService } from '../prisma/prisma.service';
import { AuditService } from '../audit/audit.service';
import { MailService } from '../auth/mail.service';
import { hashSecret } from '../auth/hashing';
import type { UserPrincipal } from '../auth/principal';

interface SummaryRow {
  id: string;
  name: string;
  code: string;
  status: string;
  createdAt: Date;
  _count: { teams: number; employees: number };
  users: { id: string }[];
}

interface AdminRow {
  id: string;
  email: string;
  name: string;
  status: string;
  createdAt: Date;
}

function toSummary(row: SummaryRow): CompanySummary {
  return {
    id: row.id,
    name: row.name,
    code: row.code,
    status: row.status as CompanyStatus,
    teamCount: row._count.teams,
    employeeCount: row._count.employees,
    hasAdmin: row.users.length > 0,
    createdAt: row.createdAt.toISOString(),
  };
}

function toAdmin(user: AdminRow): CompanyAdmin {
  return {
    id: user.id,
    email: user.email,
    name: user.name,
    status: user.status,
    createdAt: user.createdAt.toISOString(),
  };
}

/**
 * Company management (§2/§3.1). SUPER_ADMIN-only (enforced by the controller guard).
 * Every mutation writes an explicit AuditLog row partitioned under the target company's
 * companyId (the actor is SUPER_ADMIN with no company of their own).
 */
@Injectable()
export class CompaniesService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly audit: AuditService,
    private readonly mail: MailService,
    private readonly config: ConfigService<Env, true>,
  ) {}

  async create(input: CreateCompanyInput, actor: UserPrincipal, ip?: string): Promise<CompanyDetail> {
    let company: { id: string };
    try {
      company = await this.prisma.guarded.company.create({
        data: { name: input.name, code: input.code },
        select: { id: true },
      });
    } catch (err) {
      if (err instanceof Prisma.PrismaClientKnownRequestError && err.code === 'P2002') {
        throw new ConflictException(`Company code "${input.code}" is already in use`);
      }
      throw err;
    }

    await this.audit.record({
      actorType: 'USER',
      actorId: actor.userId,
      companyId: company.id,
      action: 'COMPANY_CREATED',
      targetType: 'Company',
      targetId: company.id,
      metadata: { name: input.name, code: input.code },
      ipAddress: ip ?? null,
    });

    return this.getDetail(company.id);
  }

  async list(): Promise<CompanySummary[]> {
    const rows = await this.prisma.guarded.company.findMany({
      orderBy: { createdAt: 'desc' },
      select: {
        id: true,
        name: true,
        code: true,
        status: true,
        createdAt: true,
        _count: { select: { teams: true, employees: true } },
        users: { where: { role: UserRole.COMPANY_ADMIN }, select: { id: true }, take: 1 },
      },
    });
    return rows.map(toSummary);
  }

  async getDetail(id: string): Promise<CompanyDetail> {
    const row = await this.prisma.guarded.company.findUnique({
      where: { id },
      select: {
        id: true,
        name: true,
        code: true,
        status: true,
        createdAt: true,
        _count: { select: { teams: true, employees: true } },
        users: {
          where: { role: UserRole.COMPANY_ADMIN },
          select: { id: true, email: true, name: true, status: true, createdAt: true },
          orderBy: { createdAt: 'asc' },
          take: 1,
        },
      },
    });
    if (!row) {
      throw new NotFoundException('Company not found');
    }
    return { ...toSummary(row), admin: row.users[0] ? toAdmin(row.users[0]) : null };
  }

  async update(
    id: string,
    input: UpdateCompanyInput,
    actor: UserPrincipal,
    ip?: string,
  ): Promise<CompanyDetail> {
    await this.ensureExists(id);
    await this.prisma.guarded.company.update({
      where: { id },
      data: { name: input.name, status: input.status },
    });

    await this.audit.record({
      actorType: 'USER',
      actorId: actor.userId,
      companyId: id,
      action: 'COMPANY_UPDATED',
      targetType: 'Company',
      targetId: id,
      metadata: {
        ...(input.name !== undefined ? { name: input.name } : {}),
        ...(input.status !== undefined ? { status: input.status } : {}),
      },
      ipAddress: ip ?? null,
    });

    return this.getDetail(id);
  }

  async provisionAdmin(
    id: string,
    input: ProvisionCompanyAdminInput,
    actor: UserPrincipal,
    ip?: string,
  ): Promise<ProvisionCompanyAdminResult> {
    const company = await this.ensureExists(id);

    // One Company Admin per company (§2).
    const existing = await this.prisma.guarded.user.findFirst({
      where: { companyId: id, role: UserRole.COMPANY_ADMIN },
      select: { id: true },
    });
    if (existing) {
      throw new ConflictException('This company already has an admin');
    }

    const tempPassword = randomBytes(12).toString('base64url');
    let user: AdminRow;
    try {
      user = await this.prisma.guarded.user.create({
        data: {
          email: input.email,
          name: input.name,
          role: UserRole.COMPANY_ADMIN,
          companyId: id,
          passwordHash: await hashSecret(tempPassword),
          status: 'ACTIVE',
        },
        select: { id: true, email: true, name: true, status: true, createdAt: true },
      });
    } catch (err) {
      if (err instanceof Prisma.PrismaClientKnownRequestError && err.code === 'P2002') {
        throw new ConflictException(`Email "${input.email}" is already in use`);
      }
      throw err;
    }

    await this.mail.sendCompanyAdminInvite(user.email, company.name, tempPassword);

    await this.audit.record({
      actorType: 'USER',
      actorId: actor.userId,
      companyId: id,
      action: 'COMPANY_ADMIN_PROVISIONED',
      targetType: 'User',
      targetId: user.id,
      metadata: { email: user.email },
      ipAddress: ip ?? null,
    });

    return { admin: toAdmin(user), ...(this.isProd() ? {} : { devPassword: tempPassword }) };
  }

  private async ensureExists(id: string): Promise<{ id: string; name: string }> {
    const company = await this.prisma.guarded.company.findUnique({
      where: { id },
      select: { id: true, name: true },
    });
    if (!company) {
      throw new NotFoundException('Company not found');
    }
    return company;
  }

  private isProd(): boolean {
    return this.config.get('NODE_ENV', { infer: true }) === 'production';
  }
}
