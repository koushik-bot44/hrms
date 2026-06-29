import { Body, Controller, Get, Post, Req, Res } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { ApiTags } from '@nestjs/swagger';
import type { Request, Response } from 'express';
import {
  type AuthResult,
  EmployeeOtpRequestSchema,
  type EmployeeOtpRequestInput,
  EmployeeOtpVerifySchema,
  type EmployeeOtpVerifyInput,
  type OtpRequestResult,
  type Session,
  StaffLoginSchema,
  type StaffLoginInput,
} from '@ihrms/shared';
import type { Env } from '../config/env.validation';
import { ZodValidationPipe } from '../common/pipes/zod-validation.pipe';
import type { AuditActor } from '../audit/audit.service';
import { AuthService, type IssuedSession } from './auth.service';
import { refreshCookieOptions } from './cookies';
import { Public } from './decorators/public.decorator';
import { CurrentUser } from './decorators/current-user.decorator';
import { type Principal, toSession } from './principal';

interface AuthRequest extends Request {
  auditActor?: AuditActor;
}

@ApiTags('auth')
@Controller('auth')
export class AuthController {
  constructor(
    private readonly auth: AuthService,
    private readonly config: ConfigService<Env, true>,
  ) {}

  @Public()
  @Post('login')
  login(
    @Body(new ZodValidationPipe(StaffLoginSchema)) body: StaffLoginInput,
    @Req() req: AuthRequest,
    @Res({ passthrough: true }) res: Response,
  ): Promise<AuthResult> {
    return this.auth.loginStaff(body).then((issued) => this.complete(issued, req, res));
  }

  @Public()
  @Post('employee/request-otp')
  requestOtp(
    @Body(new ZodValidationPipe(EmployeeOtpRequestSchema)) body: EmployeeOtpRequestInput,
  ): Promise<OtpRequestResult> {
    return this.auth.requestEmployeeOtp(body);
  }

  @Public()
  @Post('employee/verify-otp')
  verifyOtp(
    @Body(new ZodValidationPipe(EmployeeOtpVerifySchema)) body: EmployeeOtpVerifyInput,
    @Req() req: AuthRequest,
    @Res({ passthrough: true }) res: Response,
  ): Promise<AuthResult> {
    return this.auth.verifyEmployeeOtp(body).then((issued) => this.complete(issued, req, res));
  }

  @Public()
  @Post('refresh')
  refresh(@Req() req: AuthRequest, @Res({ passthrough: true }) res: Response): Promise<AuthResult> {
    const cookieName = this.config.get('REFRESH_COOKIE_NAME', { infer: true });
    const token = req.cookies?.[cookieName];
    return this.auth.refresh(token).then((issued) => this.complete(issued, req, res));
  }

  @Public()
  @Post('logout')
  logout(@Res({ passthrough: true }) res: Response): { ok: true } {
    const cookieName = this.config.get('REFRESH_COOKIE_NAME', { infer: true });
    res.clearCookie(cookieName, refreshCookieOptions(this.config));
    return { ok: true };
  }

  @Get('me')
  me(@CurrentUser() principal: Principal): Session {
    return toSession(principal);
  }

  /** Set the refresh cookie and the audit actor (req.user is unset on @Public routes). */
  private complete(issued: IssuedSession, req: AuthRequest, res: Response): AuthResult {
    const cookieName = this.config.get('REFRESH_COOKIE_NAME', { infer: true });
    res.cookie(cookieName, issued.refreshToken, refreshCookieOptions(this.config));

    const s = issued.result.session;
    req.auditActor =
      s.type === 'USER'
        ? { actorType: 'USER', actorId: s.userId, companyId: s.companyId }
        : { actorType: 'EMPLOYEE', actorId: s.employeeId, companyId: s.companyId };

    return issued.result;
  }
}
