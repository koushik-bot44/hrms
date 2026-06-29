import { randomBytes } from 'node:crypto';
import {
  BadRequestException,
  ConflictException,
  ForbiddenException,
  Injectable,
  NotFoundException,
} from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { Prisma, UserRole } from '@prisma/client';
import type {
  AssignMemberInput,
  AssignMemberResult,
  AssignableUser,
  CreateTeamInput,
  TeamDetail,
  TeamMember,
  TeamRole,
  TeamSummary,
  UpdateTeamInput,
} from '@ihrms/shared';
import type { Env } from '../config/env.validation';
import { PrismaService } from '../prisma/prisma.service';
import { AuditService } from '../audit/audit.service';
import { MailService } from '../auth/mail.service';
import { hashSecret } from '../auth/hashing';
import type { UserPrincipal } from '../auth/principal';

const MEMBER_SELECT = {
  id: true,
  name: true,
  email: true,
  role: true,
  status: true,
} satisfies Prisma.UserSelect;

interface MemberRow {
  id: string;
  name: string;
  email: string;
  role: UserRole;
  status: string;
}

interface TeamRow {
  id: string;
  name: string;
  createdAt: Date;
  hr: MemberRow | null;
  manager: MemberRow | null;
  _count: { members: number };
}

function toMember(user: MemberRow): TeamMember {
  return {
    id: user.id,
    name: user.name,
    email: user.email,
    role: user.role as TeamMember['role'],
    status: user.status,
  };
}

function toSummary(team: TeamRow): TeamSummary {
  return {
    id: team.id,
    name: team.name,
    hr: team.hr ? toMember(team.hr) : null,
    manager: team.manager ? toMember(team.manager) : null,
    memberCount: team._count.members,
    createdAt: team.createdAt.toISOString(),
  };
}

/**
 * Team management (§2/§3.1). COMPANY_ADMIN-only (controller guard); every query is
 * filtered by the admin's `companyId` so there is no cross-tenant access. The single
 * hrUserId/managerUserId columns structurally enforce exactly one HR + one Manager.
 */
@Injectable()
export class TeamsService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly audit: AuditService,
    private readonly mail: MailService,
    private readonly config: ConfigService<Env, true>,
  ) {}

  async create(input: CreateTeamInput, actor: UserPrincipal, ip?: string): Promise<TeamDetail> {
    const companyId = this.companyOf(actor);
    const team = await this.prisma.guarded.team.create({
      data: { name: input.name, companyId },
      select: { id: true },
    });
    await this.audit.record({
      actorType: 'USER',
      actorId: actor.userId,
      companyId,
      action: 'TEAM_CREATED',
      targetType: 'Team',
      targetId: team.id,
      metadata: { name: input.name },
      ipAddress: ip ?? null,
    });
    return this.getDetail(team.id, actor);
  }

  async list(actor: UserPrincipal): Promise<TeamSummary[]> {
    const companyId = this.companyOf(actor);
    const rows = await this.prisma.guarded.team.findMany({
      where: { companyId },
      orderBy: { createdAt: 'desc' },
      select: {
        id: true,
        name: true,
        createdAt: true,
        hr: { select: MEMBER_SELECT },
        manager: { select: MEMBER_SELECT },
        _count: { select: { members: true } },
      },
    });
    return rows.map(toSummary);
  }

  async getDetail(id: string, actor: UserPrincipal): Promise<TeamDetail> {
    const companyId = this.companyOf(actor);
    const team = await this.prisma.guarded.team.findFirst({
      where: { id, companyId },
      select: {
        id: true,
        name: true,
        createdAt: true,
        hr: { select: MEMBER_SELECT },
        manager: { select: MEMBER_SELECT },
        _count: { select: { members: true } },
        members: { select: MEMBER_SELECT, orderBy: { name: 'asc' } },
      },
    });
    if (!team) {
      throw new NotFoundException('Team not found');
    }
    return { ...toSummary(team), members: team.members.map(toMember) };
  }

  async update(
    id: string,
    input: UpdateTeamInput,
    actor: UserPrincipal,
    ip?: string,
  ): Promise<TeamDetail> {
    const companyId = this.companyOf(actor);
    await this.ensureTeam(id, companyId);
    await this.prisma.guarded.team.update({ where: { id }, data: { name: input.name } });
    await this.audit.record({
      actorType: 'USER',
      actorId: actor.userId,
      companyId,
      action: 'TEAM_UPDATED',
      targetType: 'Team',
      targetId: id,
      metadata: { name: input.name },
      ipAddress: ip ?? null,
    });
    return this.getDetail(id, actor);
  }

  async remove(id: string, actor: UserPrincipal, ip?: string): Promise<void> {
    const companyId = this.companyOf(actor);
    await this.ensureTeam(id, companyId);
    const approvals = await this.prisma.guarded.approvalRequest.count({ where: { teamId: id } });
    if (approvals > 0) {
      throw new ConflictException('Team has approval history and cannot be deleted');
    }
    // Detach members (clears their teamId), then delete the team.
    await this.prisma.guarded.$transaction([
      this.prisma.guarded.user.updateMany({ where: { teamId: id }, data: { teamId: null } }),
      this.prisma.guarded.team.delete({ where: { id } }),
    ]);
    await this.audit.record({
      actorType: 'USER',
      actorId: actor.userId,
      companyId,
      action: 'TEAM_DELETED',
      targetType: 'Team',
      targetId: id,
      ipAddress: ip ?? null,
    });
  }

  async assignableUsers(actor: UserPrincipal, role: string): Promise<AssignableUser[]> {
    const companyId = this.companyOf(actor);
    if (role !== UserRole.HR && role !== UserRole.MANAGER) {
      throw new BadRequestException('role must be HR or MANAGER');
    }
    const users = await this.prisma.guarded.user.findMany({
      where: { companyId, role, teamId: null },
      orderBy: { name: 'asc' },
      select: MEMBER_SELECT,
    });
    return users.map(toMember);
  }

  /** Assign the HR or Manager slot by selecting an existing user or creating a new one. */
  async assign(
    id: string,
    role: TeamRole,
    input: AssignMemberInput,
    actor: UserPrincipal,
    ip?: string,
  ): Promise<AssignMemberResult> {
    const companyId = this.companyOf(actor);
    const team = await this.prisma.guarded.team.findFirst({
      where: { id, companyId },
      select: { id: true, hrUserId: true, managerUserId: true },
    });
    if (!team) {
      throw new NotFoundException('Team not found');
    }

    const isHr = role === UserRole.HR;
    const currentUserId = isHr ? team.hrUserId : team.managerUserId;
    const otherUserId = isHr ? team.managerUserId : team.hrUserId;

    let userId: string;
    let devPassword: string | undefined;

    if ('userId' in input) {
      const user = await this.prisma.guarded.user.findFirst({
        where: { id: input.userId, companyId },
        select: { id: true, role: true, teamId: true },
      });
      if (!user) {
        throw new NotFoundException('User not found in your company');
      }
      if (user.role !== role) {
        throw new BadRequestException(`Selected user is not ${role}`);
      }
      if (user.teamId && user.teamId !== id) {
        throw new ConflictException('User is already assigned to another team');
      }
      userId = user.id;
    } else {
      const tempPassword = randomBytes(12).toString('base64url');
      try {
        const created = await this.prisma.guarded.user.create({
          data: {
            name: input.name,
            email: input.email,
            role,
            companyId,
            teamId: id,
            passwordHash: await hashSecret(tempPassword),
            status: 'ACTIVE',
          },
          select: { id: true },
        });
        userId = created.id;
      } catch (err) {
        if (err instanceof Prisma.PrismaClientKnownRequestError && err.code === 'P2002') {
          throw new ConflictException(`Email "${input.email}" is already in use`);
        }
        throw err;
      }
      await this.mail.sendStaffInvite(input.email, role, tempPassword);
      devPassword = this.isProd() ? undefined : tempPassword;
    }

    if (otherUserId && otherUserId === userId) {
      throw new ConflictException('This person already holds the other role on this team');
    }

    await this.prisma.guarded.$transaction(async (tx) => {
      // Detach a replaced holder so a team keeps exactly one person in the slot.
      if (currentUserId && currentUserId !== userId) {
        await tx.user.update({ where: { id: currentUserId }, data: { teamId: null } });
      }
      await tx.user.update({ where: { id: userId }, data: { teamId: id, role } });
      await tx.team.update({
        where: { id },
        data: isHr ? { hrUserId: userId } : { managerUserId: userId },
      });
    });

    await this.audit.record({
      actorType: 'USER',
      actorId: actor.userId,
      companyId,
      action: isHr ? 'TEAM_HR_ASSIGNED' : 'TEAM_MANAGER_ASSIGNED',
      targetType: 'Team',
      targetId: id,
      metadata: { userId, role },
      ipAddress: ip ?? null,
    });

    const detail = await this.getDetail(id, actor);
    return devPassword ? { team: detail, devPassword } : { team: detail };
  }

  private companyOf(actor: UserPrincipal): string {
    if (!actor.companyId) {
      throw new ForbiddenException('No company in scope');
    }
    return actor.companyId;
  }

  private async ensureTeam(id: string, companyId: string): Promise<void> {
    const team = await this.prisma.guarded.team.findFirst({
      where: { id, companyId },
      select: { id: true },
    });
    if (!team) {
      throw new NotFoundException('Team not found');
    }
  }

  private isProd(): boolean {
    return this.config.get('NODE_ENV', { infer: true }) === 'production';
  }
}
