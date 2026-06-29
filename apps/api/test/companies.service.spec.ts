import { ConflictException } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { UserRole } from '@ihrms/shared';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { CompaniesService } from '../src/companies/companies.service';
import { MailService } from '../src/auth/mail.service';
import { AuditService } from '../src/audit/audit.service';
import { verifySecret } from '../src/auth/hashing';
import { PrismaService } from '../src/prisma/prisma.service';
import type { UserPrincipal } from '../src/auth/principal';

const DB = process.env.DPP_TEST_DB_URL;
const describeIf = DB ? describe : describe.skip;

const PREFIX = 'ZCO'; // company-code prefix (upper-alnum)
const EMAIL = 'zco-admin-';

const config = new ConfigService({ NODE_ENV: 'test', SMTP_HOST: '' }) as ConfigService<never, true>;

const superAdmin: UserPrincipal = {
  type: 'USER',
  userId: 'super-1',
  email: 'super@t.local',
  name: 'Super',
  role: UserRole.SUPER_ADMIN,
  companyId: null,
  teamId: null,
};

describeIf('CompaniesService (§2/§3.1)', () => {
  let prisma: PrismaService;
  let service: CompaniesService;

  beforeAll(async () => {
    process.env.DATABASE_URL = DB as string;
    prisma = new PrismaService();
    await prisma.$connect();
    service = new CompaniesService(prisma, new AuditService(prisma), new MailService(config), config);
    await cleanup(prisma);
  }, 60_000);

  afterAll(async () => {
    if (prisma) {
      await cleanup(prisma);
      await prisma.$disconnect();
    }
  });

  it('creates a company and lists it with zero counts and no admin', async () => {
    const created = await service.create({ name: 'Acme Co', code: `${PREFIX}A` }, superAdmin, '127.0.0.1');
    expect(created.code).toBe(`${PREFIX}A`);
    expect(created.teamCount).toBe(0);
    expect(created.employeeCount).toBe(0);
    expect(created.hasAdmin).toBe(false);
    expect(created.admin).toBeNull();

    const list = await service.list();
    expect(list.some((c) => c.code === `${PREFIX}A`)).toBe(true);
  });

  it('rejects a duplicate company code (409)', async () => {
    await expect(
      service.create({ name: 'Dupe', code: `${PREFIX}A` }, superAdmin, '127.0.0.1'),
    ).rejects.toThrow(ConflictException);
  });

  it('provisions a Company Admin (argon2 password) and writes an audit row scoped to the company', async () => {
    const company = await service.create({ name: 'Beta Co', code: `${PREFIX}B` }, superAdmin, '127.0.0.1');

    const result = await service.provisionAdmin(
      company.id,
      { name: 'Bea Admin', email: `${EMAIL}b@t.local` },
      superAdmin,
      '127.0.0.1',
    );
    expect(result.admin.email).toBe(`${EMAIL}b@t.local`);
    expect(result.devPassword).toBeTruthy();

    // The created user is a COMPANY_ADMIN of this company with a valid argon2 hash.
    const user = await prisma.guarded.user.findUnique({
      where: { email: `${EMAIL}b@t.local` },
      select: { role: true, companyId: true, passwordHash: true },
    });
    expect(user?.role).toBe(UserRole.COMPANY_ADMIN);
    expect(user?.companyId).toBe(company.id);
    expect(await verifySecret(user?.passwordHash ?? '', result.devPassword as string)).toBe(true);

    // Detail now reports the admin; list shows hasAdmin.
    const detail = await service.getDetail(company.id);
    expect(detail.hasAdmin).toBe(true);
    expect(detail.admin?.email).toBe(`${EMAIL}b@t.local`);

    // Audit row partitioned under the company (companyId set).
    const audit = await prisma.guarded.auditLog.findFirst({
      where: { action: 'COMPANY_ADMIN_PROVISIONED', companyId: company.id },
    });
    expect(audit).toBeTruthy();
    expect(audit?.actorId).toBe(superAdmin.userId);
  });

  it('refuses a second admin for the same company (409)', async () => {
    const company = await service.create({ name: 'Gamma Co', code: `${PREFIX}G` }, superAdmin, '127.0.0.1');
    await service.provisionAdmin(company.id, { name: 'One', email: `${EMAIL}g1@t.local` }, superAdmin);
    await expect(
      service.provisionAdmin(company.id, { name: 'Two', email: `${EMAIL}g2@t.local` }, superAdmin),
    ).rejects.toThrow(ConflictException);
  });

  it('updates name and status', async () => {
    const company = await service.create({ name: 'Delta Co', code: `${PREFIX}D` }, superAdmin);
    const updated = await service.update(company.id, { name: 'Delta Renamed', status: 'SUSPENDED' }, superAdmin);
    expect(updated.name).toBe('Delta Renamed');
    expect(updated.status).toBe('SUSPENDED');
  });
});

async function cleanup(prisma: PrismaService): Promise<void> {
  await prisma.guarded.user.deleteMany({ where: { email: { startsWith: EMAIL } } });
  await prisma.guarded.company.deleteMany({ where: { code: { startsWith: PREFIX } } });
}
