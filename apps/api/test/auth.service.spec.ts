import { UnauthorizedException } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { JwtService } from '@nestjs/jwt';
import { UserRole } from '@ihrms/shared';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { AuthService } from '../src/auth/auth.service';
import { MailService } from '../src/auth/mail.service';
import { hashSecret } from '../src/auth/hashing';
import { PrismaService } from '../src/prisma/prisma.service';

const DB = process.env.DPP_TEST_DB_URL;
const describeIf = DB ? describe : describe.skip;

const EMAIL = 'zq-auth-';
const CODE = 'ZQSC-EMP-000001';
const PASSWORD = 'Sup3rSecret!';

const testConfig = new ConfigService({
  NODE_ENV: 'test',
  OTP_TTL: 300,
  JWT_SECRET: 'test-access-secret',
  REFRESH_SECRET: 'test-refresh-secret',
  ACCESS_TOKEN_TTL: '15m',
  REFRESH_TOKEN_TTL: '7d',
  REFRESH_COOKIE_NAME: 'ihrms_refresh',
  SMTP_HOST: '',
}) as ConfigService<never, true>;

describeIf('AuthService (§6)', () => {
  let prisma: PrismaService;
  let auth: AuthService;
  let employeeId: string;

  beforeAll(async () => {
    process.env.DATABASE_URL = DB as string;
    prisma = new PrismaService();
    await prisma.$connect();
    auth = new AuthService(prisma, new JwtService({}), testConfig, new MailService(testConfig));
    await cleanup(prisma);

    const company = await prisma.guarded.company.create({ data: { name: 'Auth Co', code: 'ZQSC' } });
    const staff = await prisma.guarded.user.create({
      data: {
        email: `${EMAIL}staff@t.local`,
        name: 'Staffer',
        role: UserRole.COMPANY_ADMIN,
        companyId: company.id,
        passwordHash: await hashSecret(PASSWORD),
        status: 'ACTIVE',
      },
    });
    const employee = await prisma.guarded.employee.create({
      data: {
        employeeCode: CODE,
        email: `${EMAIL}emp@t.local`,
        companyId: company.id,
        onboardingHrId: staff.id,
      },
    });
    employeeId = employee.id;
  }, 60_000);

  afterAll(async () => {
    if (prisma) {
      await cleanup(prisma);
      await prisma.$disconnect();
    }
  });

  describe('staff login', () => {
    it('issues a session for the correct password', async () => {
      const { result } = await auth.loginStaff({ email: `${EMAIL}staff@t.local`, password: PASSWORD });
      expect(result.accessToken).toBeTruthy();
      expect(result.session.type).toBe('USER');
      if (result.session.type === 'USER') {
        expect(result.session.role).toBe(UserRole.COMPANY_ADMIN);
      }
    });

    it('rejects a wrong password', async () => {
      await expect(
        auth.loginStaff({ email: `${EMAIL}staff@t.local`, password: 'wrong-password' }),
      ).rejects.toThrow(UnauthorizedException);
    });

    it('rejects an unknown email', async () => {
      await expect(
        auth.loginStaff({ email: `${EMAIL}nobody@t.local`, password: PASSWORD }),
      ).rejects.toThrow(UnauthorizedException);
    });
  });

  describe('employee OTP', () => {
    it('issues an OTP that verifies exactly once (single-use)', async () => {
      const req = await auth.requestEmployeeOtp({ employeeCode: CODE, email: `${EMAIL}emp@t.local` });
      expect(req.sent).toBe(true);
      expect(req.devOtp).toMatch(/^\d{6}$/);
      const otp = req.devOtp as string;

      const { result } = await auth.verifyEmployeeOtp({ employeeCode: CODE, otp });
      expect(result.session.type).toBe('EMPLOYEE');

      // Second use must fail — the OTP was cleared on first verify.
      await expect(auth.verifyEmployeeOtp({ employeeCode: CODE, otp })).rejects.toThrow(
        UnauthorizedException,
      );
    });

    it('rejects an expired OTP', async () => {
      const req = await auth.requestEmployeeOtp({ employeeCode: CODE, email: `${EMAIL}emp@t.local` });
      const otp = req.devOtp as string;
      // Force expiry.
      await prisma.guarded.employee.update({
        where: { id: employeeId },
        data: { otpExpiresAt: new Date(Date.now() - 1000) },
      });
      await expect(auth.verifyEmployeeOtp({ employeeCode: CODE, otp })).rejects.toThrow(
        UnauthorizedException,
      );
    });

    it('rejects a wrong OTP', async () => {
      await auth.requestEmployeeOtp({ employeeCode: CODE, email: `${EMAIL}emp@t.local` });
      await expect(auth.verifyEmployeeOtp({ employeeCode: CODE, otp: '000000' })).rejects.toThrow(
        UnauthorizedException,
      );
    });

    it('does not reveal whether an employee exists (wrong email still returns sent)', async () => {
      const req = await auth.requestEmployeeOtp({ employeeCode: CODE, email: `${EMAIL}wrong@t.local` });
      expect(req.sent).toBe(true);
      expect(req.devOtp).toBeUndefined();
    });
  });
});

async function cleanup(prisma: PrismaService): Promise<void> {
  await prisma.guarded.employee.deleteMany({ where: { employeeCode: { startsWith: 'ZQSC' } } });
  await prisma.guarded.user.deleteMany({ where: { email: { startsWith: EMAIL } } });
  await prisma.guarded.company.deleteMany({ where: { code: { startsWith: 'ZQSC' } } });
}
