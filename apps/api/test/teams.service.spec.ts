import { BadRequestException, NotFoundException } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { UserRole } from '@ihrms/shared';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { TeamsService } from '../src/teams/teams.service';
import { MailService } from '../src/auth/mail.service';
import { AuditService } from '../src/audit/audit.service';
import { PrismaService } from '../src/prisma/prisma.service';
import type { UserPrincipal } from '../src/auth/principal';

const DB = process.env.DPP_TEST_DB_URL;
const describeIf = DB ? describe : describe.skip;

const PREFIX = 'ZT'; // company-code prefix
const EMAIL = 'zt-team-';
const config = new ConfigService({ NODE_ENV: 'test', SMTP_HOST: '' }) as ConfigService<never, true>;

const ids: Record<string, string> = {};
const admin = (companyKey: string): UserPrincipal => ({
  type: 'USER',
  userId: `admin-${companyKey}`,
  email: `${EMAIL}admin-${companyKey}@t.local`,
  name: 'Admin',
  role: UserRole.COMPANY_ADMIN,
  companyId: ids[companyKey],
  teamId: null,
});

describeIf('TeamsService (§2/§3.1)', () => {
  let prisma: PrismaService;
  let service: TeamsService;

  beforeAll(async () => {
    process.env.DATABASE_URL = DB as string;
    prisma = new PrismaService();
    await prisma.$connect();
    service = new TeamsService(prisma, new AuditService(prisma), new MailService(config), config);
    await cleanup(prisma);
    ids.A = (await prisma.guarded.company.create({ data: { name: 'Co A', code: `${PREFIX}A` } })).id;
    ids.B = (await prisma.guarded.company.create({ data: { name: 'Co B', code: `${PREFIX}B` } })).id;
  }, 60_000);

  afterAll(async () => {
    if (prisma) {
      await cleanup(prisma);
      await prisma.$disconnect();
    }
  });

  it('creates a team scoped to the admin company and lists it', async () => {
    const team = await service.create({ name: 'Engineering' }, admin('A'));
    expect(team.hr).toBeNull();
    expect(team.manager).toBeNull();
    ids.teamA = team.id;

    const list = await service.list(admin('A'));
    expect(list.some((t) => t.id === team.id)).toBe(true);

    // Company B's admin can't see company A's team.
    expect((await service.list(admin('B'))).some((t) => t.id === team.id)).toBe(false);
    await expect(service.getDetail(team.id, admin('B'))).rejects.toThrow(NotFoundException);
  });

  it('assigns a new HR and a new Manager (one each)', async () => {
    const hr = await service.assign(
      ids.teamA,
      UserRole.HR,
      { name: 'Hank HR', email: `${EMAIL}hr1@t.local` },
      admin('A'),
    );
    expect(hr.team.hr?.email).toBe(`${EMAIL}hr1@t.local`);
    expect(hr.team.hr?.role).toBe(UserRole.HR);
    expect(hr.devPassword).toBeTruthy();

    const mgr = await service.assign(
      ids.teamA,
      UserRole.MANAGER,
      { name: 'Mia Manager', email: `${EMAIL}mgr1@t.local` },
      admin('A'),
    );
    expect(mgr.team.manager?.email).toBe(`${EMAIL}mgr1@t.local`);
    expect(mgr.team.hr?.email).toBe(`${EMAIL}hr1@t.local`); // HR unchanged
    expect(mgr.team.members).toHaveLength(2);
  });

  it('replacing the HR keeps exactly one HR and detaches the previous holder', async () => {
    const before = await service.getDetail(ids.teamA, admin('A'));
    const previousHrId = before.hr?.id as string;

    const replaced = await service.assign(
      ids.teamA,
      UserRole.HR,
      { name: 'New HR', email: `${EMAIL}hr2@t.local` },
      admin('A'),
    );
    expect(replaced.team.hr?.email).toBe(`${EMAIL}hr2@t.local`);
    expect(replaced.team.members).toHaveLength(2); // still HR + Manager, not 3

    const oldHr = await prisma.guarded.user.findUnique({
      where: { id: previousHrId },
      select: { teamId: true },
    });
    expect(oldHr?.teamId).toBeNull(); // detached
  });

  it('refuses to put the Manager into the HR slot (role mismatch)', async () => {
    const detail = await service.getDetail(ids.teamA, admin('A'));
    const managerId = detail.manager?.id as string;
    // The Manager has role MANAGER, so they are not eligible for the HR slot.
    await expect(
      service.assign(ids.teamA, UserRole.HR, { userId: managerId }, admin('A')),
    ).rejects.toThrow(BadRequestException);
  });

  it('lists assignable (unassigned, role-matching) users and attaches an existing one', async () => {
    // A free HR user in company A.
    const free = await prisma.guarded.user.create({
      data: { name: 'Free HR', email: `${EMAIL}free@t.local`, role: UserRole.HR, companyId: ids.A },
      select: { id: true },
    });

    const assignable = await service.assignableUsers(admin('A'), UserRole.HR);
    expect(assignable.some((u) => u.id === free.id)).toBe(true);

    const team = await service.create({ name: 'Sales' }, admin('A'));
    const result = await service.assign(team.id, UserRole.HR, { userId: free.id }, admin('A'));
    expect(result.team.hr?.id).toBe(free.id);

    // Now attached -> no longer assignable.
    expect((await service.assignableUsers(admin('A'), UserRole.HR)).some((u) => u.id === free.id)).toBe(
      false,
    );
  });

  it("blocks cross-company assignment and a different company's user", async () => {
    await expect(
      service.assign(ids.teamA, UserRole.HR, { name: 'X', email: `${EMAIL}x@t.local` }, admin('B')),
    ).rejects.toThrow(NotFoundException);
  });

  it('writes audit rows scoped to the company', async () => {
    const created = await prisma.guarded.auditLog.findFirst({
      where: { action: 'TEAM_CREATED', companyId: ids.A },
    });
    expect(created?.actorId).toBe('admin-A');
    const assigned = await prisma.guarded.auditLog.findFirst({
      where: { action: 'TEAM_HR_ASSIGNED', companyId: ids.A },
    });
    expect(assigned).toBeTruthy();
  });
});

async function cleanup(prisma: PrismaService): Promise<void> {
  await prisma.guarded.team.updateMany({
    where: { company: { code: { startsWith: PREFIX } } },
    data: { hrUserId: null, managerUserId: null },
  });
  await prisma.guarded.user.updateMany({
    where: { email: { startsWith: EMAIL } },
    data: { teamId: null },
  });
  await prisma.guarded.team.deleteMany({ where: { company: { code: { startsWith: PREFIX } } } });
  await prisma.guarded.user.deleteMany({ where: { email: { startsWith: EMAIL } } });
  await prisma.guarded.company.deleteMany({ where: { code: { startsWith: PREFIX } } });
}
