import { ConfigService } from '@nestjs/config';
import { EMPLOYEE_CODE_REGEX, UserRole } from '@ihrms/shared';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { EmployeesService } from '../src/employees/employees.service';
import { MailService } from '../src/auth/mail.service';
import { AuditService } from '../src/audit/audit.service';
import { PrismaService } from '../src/prisma/prisma.service';
import type { UserPrincipal } from '../src/auth/principal';

const DB = process.env.DPP_TEST_DB_URL;
const describeIf = DB ? describe : describe.skip;

const CODE = 'ZEMP'; // company code + employeeCode prefix
const EMAIL = 'zemp-';
const config = new ConfigService({
  NODE_ENV: 'test',
  SMTP_HOST: '',
  WEB_APP_URL: 'http://localhost:3001',
}) as ConfigService<never, true>;

const ids: Record<string, string> = {};
const hr = (key: string): UserPrincipal => ({
  type: 'USER',
  userId: ids[key],
  email: `${EMAIL}${key}@t.local`,
  name: key,
  role: UserRole.HR,
  companyId: ids.company,
  teamId: null,
});

describeIf('EmployeesService — onboarding (§3.2/§5)', () => {
  let prisma: PrismaService;
  let service: EmployeesService;

  beforeAll(async () => {
    process.env.DATABASE_URL = DB as string;
    prisma = new PrismaService();
    await prisma.$connect();
    service = new EmployeesService(prisma, new AuditService(prisma), new MailService(config), config);
    await cleanup(prisma);
    ids.company = (await prisma.guarded.company.create({ data: { name: 'Emp Co', code: CODE } })).id;
    ids.hr1 = (
      await prisma.guarded.user.create({
        data: { email: `${EMAIL}hr1@t.local`, name: 'HR One', role: UserRole.HR, companyId: ids.company },
      })
    ).id;
    ids.hr2 = (
      await prisma.guarded.user.create({
        data: { email: `${EMAIL}hr2@t.local`, name: 'HR Two', role: UserRole.HR, companyId: ids.company },
      })
    ).id;
  }, 60_000);

  afterAll(async () => {
    if (prisma) {
      await cleanup(prisma);
      await prisma.$disconnect();
    }
  });

  it('mints a correctly-formatted, company-scoped code starting at 000001', async () => {
    const result = await service.onboard({ email: `${EMAIL}e1@t.local` }, hr('hr1'), '127.0.0.1');
    expect(result.employee.employeeCode).toBe(`${CODE}-EMP-000001`);
    expect(EMPLOYEE_CODE_REGEX.test(result.employee.employeeCode)).toBe(true);
    expect(result.employee.status).toBe('INVITED');
    expect(result.loginUrl).toBe('http://localhost:3001/login');
  });

  it('increments the sequence for the next onboard', async () => {
    const result = await service.onboard({ email: `${EMAIL}e2@t.local` }, hr('hr1'));
    expect(result.employee.employeeCode).toBe(`${CODE}-EMP-000002`);
  });

  it('allocates distinct codes under concurrency (atomic sequence)', async () => {
    const N = 12;
    const results = await Promise.all(
      Array.from({ length: N }, (_, i) =>
        service.onboard({ email: `${EMAIL}c${i}@t.local` }, hr('hr1')),
      ),
    );
    const codes = results.map((r) => r.employee.employeeCode);
    expect(new Set(codes).size).toBe(N); // no collisions
    for (const code of codes) {
      expect(EMPLOYEE_CODE_REGEX.test(code)).toBe(true);
      expect(code.startsWith(`${CODE}-EMP-`)).toBe(true);
    }
  });

  it('the employeeCode is globally unique (DB constraint upheld)', async () => {
    const all = await prisma.guarded.employee.findMany({
      where: { employeeCode: { startsWith: CODE } },
      select: { employeeCode: true },
    });
    expect(new Set(all.map((e) => e.employeeCode)).size).toBe(all.length);
  });

  it('HR sees only the employees they onboarded (scope §6)', async () => {
    const e = await service.onboard({ email: `${EMAIL}hr2emp@t.local` }, hr('hr2'));
    const hr1List = await service.listMine(hr('hr1'));
    const hr2List = await service.listMine(hr('hr2'));
    expect(hr1List.some((x) => x.id === e.employee.id)).toBe(false); // HR1 can't see HR2's
    expect(hr2List.some((x) => x.id === e.employee.id)).toBe(true);
    expect(hr2List).toHaveLength(1);
  });

  it('writes an EMPLOYEE_ONBOARDED audit row scoped to the company', async () => {
    const audit = await prisma.guarded.auditLog.findFirst({
      where: { action: 'EMPLOYEE_ONBOARDED', companyId: ids.company },
    });
    expect(audit).toBeTruthy();
    expect(audit?.actorId).toBe(ids.hr1);
    expect(audit?.targetType).toBe('Employee');
  });
});

async function cleanup(prisma: PrismaService): Promise<void> {
  await prisma.guarded.employee.deleteMany({ where: { employeeCode: { startsWith: CODE } } });
  await prisma.guarded.employeeCodeSequence.deleteMany({
    where: { company: { code: { startsWith: CODE } } },
  });
  await prisma.guarded.user.deleteMany({ where: { email: { startsWith: EMAIL } } });
  await prisma.guarded.company.deleteMany({ where: { code: { startsWith: CODE } } });
}
