import { Injectable, UnauthorizedException } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { JwtService } from '@nestjs/jwt';
import type {
  AuthResult,
  EmployeeOtpRequestInput,
  EmployeeOtpVerifyInput,
  OtpRequestResult,
  StaffLoginInput,
} from '@ihrms/shared';
import type { Env } from '../config/env.validation';
import { PrismaService } from '../prisma/prisma.service';
import { hashSecret, verifySecret } from './hashing';
import { MailService } from './mail.service';
import {
  accessClaims,
  generateOtp,
  type Principal,
  type RefreshClaims,
  refreshClaims,
  toEmployeePrincipal,
  toSession,
  toUserPrincipal,
} from './principal';

/** Tokens + the public session returned to controllers (which set the refresh cookie). */
export interface IssuedSession {
  result: AuthResult;
  refreshToken: string;
}

@Injectable()
export class AuthService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly jwt: JwtService,
    private readonly config: ConfigService<Env, true>,
    private readonly mail: MailService,
  ) {}

  // --- Staff (email + password) ---------------------------------------------

  async loginStaff(input: StaffLoginInput): Promise<IssuedSession> {
    const invalid = new UnauthorizedException('Invalid email or password');
    const user = await this.prisma.guarded.user.findUnique({
      where: { email: input.email.toLowerCase() },
    });
    if (!user || !user.passwordHash || user.status !== 'ACTIVE') {
      throw invalid;
    }
    if (!(await verifySecret(user.passwordHash, input.password))) {
      throw invalid;
    }
    return this.issue(toUserPrincipal(user));
  }

  // --- Employee (employeeCode + email -> OTP) -------------------------------

  async requestEmployeeOtp(input: EmployeeOtpRequestInput): Promise<OtpRequestResult> {
    const ttl = this.config.get('OTP_TTL', { infer: true });
    const employee = await this.prisma.guarded.employee.findUnique({
      where: { employeeCode: input.employeeCode },
    });

    // Issue only when the code + email match; otherwise return the same shape so the
    // endpoint can't be used to enumerate employees.
    const matches = employee && employee.email.toLowerCase() === input.email.toLowerCase();
    if (matches) {
      const code = generateOtp();
      await this.prisma.guarded.employee.update({
        where: { id: employee.id },
        data: {
          otpHash: await hashSecret(code),
          otpExpiresAt: new Date(Date.now() + ttl * 1000),
        },
      });
      await this.mail.sendOtp(employee.email, code);
      return { sent: true, expiresInSeconds: ttl, ...(this.isProd() ? {} : { devOtp: code }) };
    }

    return { sent: true, expiresInSeconds: ttl };
  }

  async verifyEmployeeOtp(input: EmployeeOtpVerifyInput): Promise<IssuedSession> {
    const invalid = new UnauthorizedException('Invalid or expired code');
    const employee = await this.prisma.guarded.employee.findUnique({
      where: { employeeCode: input.employeeCode },
    });
    if (!employee || !employee.otpHash || !employee.otpExpiresAt) {
      throw invalid;
    }
    if (employee.otpExpiresAt.getTime() < Date.now()) {
      throw invalid;
    }
    if (!(await verifySecret(employee.otpHash, input.otp))) {
      throw invalid;
    }

    // Single-use: clear the OTP immediately so it can't be replayed.
    await this.prisma.guarded.employee.update({
      where: { id: employee.id },
      data: { otpHash: null, otpExpiresAt: null },
    });

    return this.issue(toEmployeePrincipal(employee));
  }

  // --- Refresh / session ----------------------------------------------------

  async refresh(refreshToken: string | undefined): Promise<IssuedSession> {
    if (!refreshToken) {
      throw new UnauthorizedException('No session');
    }
    let claims: RefreshClaims;
    try {
      claims = await this.jwt.verifyAsync<RefreshClaims>(refreshToken, {
        secret: this.config.get('REFRESH_SECRET', { infer: true }),
      });
    } catch {
      throw new UnauthorizedException('Session expired');
    }
    if (claims.typ !== 'refresh') {
      throw new UnauthorizedException('Invalid session');
    }
    // Reload from the DB so role/company/team changes and deactivation take effect.
    return this.issue(await this.reloadPrincipal(claims));
  }

  private async reloadPrincipal(claims: RefreshClaims): Promise<Principal> {
    const gone = new UnauthorizedException('Session no longer valid');
    if (claims.actor === 'USER') {
      const user = await this.prisma.guarded.user.findUnique({ where: { id: claims.sub } });
      if (!user || user.status !== 'ACTIVE') {
        throw gone;
      }
      return toUserPrincipal(user);
    }
    const employee = await this.prisma.guarded.employee.findUnique({ where: { id: claims.sub } });
    if (!employee) {
      throw gone;
    }
    return toEmployeePrincipal(employee);
  }

  // --- Token issuance -------------------------------------------------------

  private async issue(principal: Principal): Promise<IssuedSession> {
    const accessToken = await this.jwt.signAsync(accessClaims(principal), {
      secret: this.config.get('JWT_SECRET', { infer: true }),
      expiresIn: this.config.get('ACCESS_TOKEN_TTL', { infer: true }),
    });
    const refreshToken = await this.jwt.signAsync(refreshClaims(principal), {
      secret: this.config.get('REFRESH_SECRET', { infer: true }),
      expiresIn: this.config.get('REFRESH_TOKEN_TTL', { infer: true }),
    });
    return { result: { accessToken, session: toSession(principal) }, refreshToken };
  }

  private isProd(): boolean {
    return this.config.get('NODE_ENV', { infer: true }) === 'production';
  }
}
