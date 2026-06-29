import { ForbiddenException, NotFoundException } from '@nestjs/common';
import { UserRole } from '@ihrms/shared';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { AccessControlService } from '../src/auth/access-control.service';
import { PrismaService } from '../src/prisma/prisma.service';
import type { EmployeePrincipal, Principal, UserPrincipal } from '../src/auth/principal';

// Gated on a real local Postgres (migrated). Run with DPP_TEST_DB_URL set.
const DB = process.env.DPP_TEST_DB_URL;
const describeIf = DB ? describe : describe.skip;

// Fixed, prefixed identifiers so the fixture is idempotent and cleanly removable.
const P = 'ZQ'; // company-code prefix (upper-alnum, valid for employee codes)
const EMAIL = 'zq-acl-';

describeIf('AccessControlService — §6 hierarchy & tenancy', () => {
  let prisma: PrismaService;
  let acl: AccessControlService;

  // ids filled during setup
  const id: Record<string, string> = {};

  const staff = (key: string, role: UserRole, companyId: string | null, teamId: string | null): UserPrincipal => ({
    type: 'USER',
    userId: id[key],
    email: `${EMAIL}${key}@t.local`,
    name: key,
    role,
    companyId,
    teamId,
  });

  beforeAll(async () => {
    process.env.DATABASE_URL = DB as string;
    prisma = new PrismaService();
    await prisma.$connect();
    acl = new AccessControlService(prisma);
    await cleanup(prisma);

    const companyA = await prisma.guarded.company.create({ data: { name: 'Acme A', code: `${P}AA` } });
    const companyB = await prisma.guarded.company.create({ data: { name: 'Beta B', code: `${P}BB` } });
    id.companyA = companyA.id;
    id.companyB = companyB.id;

    const mkUser = async (key: string, role: UserRole, companyId: string | null) => {
      const u = await prisma.guarded.user.create({
        data: { email: `${EMAIL}${key}@t.local`, name: key, role, companyId },
      });
      id[key] = u.id;
      return u;
    };

    await mkUser('adminA', UserRole.COMPANY_ADMIN, companyA.id);
    await mkUser('hrA1', UserRole.HR, companyA.id);
    await mkUser('hrA2', UserRole.HR, companyA.id);
    await mkUser('mgrA1', UserRole.MANAGER, companyA.id);
    await mkUser('hrB1', UserRole.HR, companyB.id);
    await mkUser('mgrB1', UserRole.MANAGER, companyB.id);

    // teamA = hrA1 + mgrA1; hrA2 is NOT on mgrA1's team.
    const teamA = await prisma.guarded.team.create({
      data: { companyId: companyA.id, name: `${P}TeamA`, hrUserId: id.hrA1, managerUserId: id.mgrA1 },
    });
    const teamB = await prisma.guarded.team.create({
      data: { companyId: companyB.id, name: `${P}TeamB`, hrUserId: id.hrB1, managerUserId: id.mgrB1 },
    });
    await prisma.guarded.user.update({ where: { id: id.hrA1 }, data: { teamId: teamA.id } });
    await prisma.guarded.user.update({ where: { id: id.mgrA1 }, data: { teamId: teamA.id } });
    await prisma.guarded.user.update({ where: { id: id.hrB1 }, data: { teamId: teamB.id } });
    await prisma.guarded.user.update({ where: { id: id.mgrB1 }, data: { teamId: teamB.id } });

    const mkEmp = async (key: string, code: string, companyId: string, onboardingHrId: string) => {
      const e = await prisma.guarded.employee.create({
        data: { employeeCode: code, email: `${EMAIL}${key}@t.local`, companyId, onboardingHrId },
      });
      id[key] = e.id;
    };
    await mkEmp('empA1', `${P}AA-EMP-000001`, companyA.id, id.hrA1); // onboarded by hrA1 (on teamA)
    await mkEmp('empA2', `${P}AA-EMP-000002`, companyA.id, id.hrA2); // onboarded by hrA2 (no team / not mgrA1)
    await mkEmp('empB1', `${P}BB-EMP-000001`, companyB.id, id.hrB1);
  }, 60_000);

  afterAll(async () => {
    if (prisma) {
      await cleanup(prisma);
      await prisma.$disconnect();
    }
  });

  // principals
  const superAdmin = (): Principal => staff('super', UserRole.SUPER_ADMIN, null, null);
  const adminA = (): Principal => staff('adminA', UserRole.COMPANY_ADMIN, id.companyA, null);
  const hrA1 = (): Principal => staff('hrA1', UserRole.HR, id.companyA, id.teamA ?? null);
  const hrA2 = (): Principal => staff('hrA2', UserRole.HR, id.companyA, null);
  const mgrA1 = (): Principal => staff('mgrA1', UserRole.MANAGER, id.companyA, id.teamA ?? null);
  const empA1P = (): EmployeePrincipal => ({
    type: 'EMPLOYEE',
    employeeId: id.empA1,
    employeeCode: `${P}AA-EMP-000001`,
    email: `${EMAIL}empA1@t.local`,
    companyId: id.companyA,
  });

  const can = (p: Principal, empKey: string) =>
    acl.assertCanAccessEmployee(p, id[empKey]).then(
      () => true,
      (e) => {
        if (e instanceof ForbiddenException || e instanceof NotFoundException) return false;
        throw e;
      },
    );

  it('SUPER_ADMIN sees every company', async () => {
    expect(await can(superAdmin(), 'empA1')).toBe(true);
    expect(await can(superAdmin(), 'empB1')).toBe(true);
  });

  it('COMPANY_ADMIN: own company only (cross-company denied)', async () => {
    expect(await can(adminA(), 'empA1')).toBe(true);
    expect(await can(adminA(), 'empB1')).toBe(false);
  });

  it('HR: own onboarded only (other HR same company denied, cross-company denied)', async () => {
    expect(await can(hrA1(), 'empA1')).toBe(true);
    expect(await can(hrA1(), 'empA2')).toBe(false);
    expect(await can(hrA1(), 'empB1')).toBe(false);
    expect(await can(hrA2(), 'empA2')).toBe(true);
    expect(await can(hrA2(), 'empA1')).toBe(false);
  });

  it("MANAGER: own team's employees only", async () => {
    expect(await can(mgrA1(), 'empA1')).toBe(true); // onboarded by hrA1 on mgrA1's team
    expect(await can(mgrA1(), 'empA2')).toBe(false); // onboarded by hrA2, not on the team
    expect(await can(mgrA1(), 'empB1')).toBe(false); // cross-company
  });

  it('EMPLOYEE: own record only', async () => {
    expect(await can(empA1P(), 'empA1')).toBe(true);
    expect(await can(empA1P(), 'empA2')).toBe(false);
    expect(await can(empA1P(), 'empB1')).toBe(false);
  });

  it('assertCanAccessEmployee throws Forbidden cross-company and NotFound for missing', async () => {
    await expect(acl.assertCanAccessEmployee(adminA(), id.empB1)).rejects.toThrow(ForbiddenException);
    await expect(acl.assertCanAccessEmployee(superAdmin(), 'does-not-exist')).rejects.toThrow(
      NotFoundException,
    );
  });
});

/** Remove fixture rows in FK-safe order, scoped by the test prefix. */
async function cleanup(prisma: PrismaService): Promise<void> {
  await prisma.guarded.employee.deleteMany({ where: { employeeCode: { startsWith: P } } });
  await prisma.guarded.user.updateMany({
    where: { email: { startsWith: EMAIL } },
    data: { teamId: null },
  });
  await prisma.guarded.team.deleteMany({ where: { name: { startsWith: P } } });
  await prisma.guarded.user.deleteMany({ where: { email: { startsWith: EMAIL } } });
  await prisma.guarded.company.deleteMany({ where: { code: { startsWith: P } } });
}
