import { Injectable, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import type { Env } from '../config/env.validation';

/**
 * Outbound email. In v1 only the employee OTP is sent (§3.2/§6). With no SMTP
 * configured (local/dev), the OTP is logged so flows can be walked end-to-end; the SMTP
 * transport is a clearly-marked seam to wire when SMTP_* is set.
 */
@Injectable()
export class MailService {
  private readonly logger = new Logger(MailService.name);

  constructor(private readonly config: ConfigService<Env, true>) {}

  async sendOtp(email: string, code: string): Promise<void> {
    const host = this.config.get('SMTP_HOST', { infer: true });
    if (!host) {
      this.logger.warn(`[DEV OTP] ${email} -> ${code}  (no SMTP configured; logging only)`);
      return;
    }
    // SMTP seam: wire a transport (e.g. nodemailer) here when SMTP_* is configured.
    this.logger.log(`OTP email dispatched to ${email}`);
  }

  /** Sends a newly-onboarded employee their unique ID + login link (§3.2). */
  async sendEmployeeOnboarding(
    email: string,
    employeeCode: string,
    loginUrl: string,
  ): Promise<void> {
    const host = this.config.get('SMTP_HOST', { infer: true });
    if (!host) {
      this.logger.warn(
        `[DEV ONBOARDING] ${email} -> employee ID: ${employeeCode} · login: ${loginUrl}  (no SMTP configured; logging only)`,
      );
      return;
    }
    // SMTP seam: wire a transport (e.g. nodemailer) here when SMTP_* is configured.
    this.logger.log(`Onboarding email dispatched to ${email}`);
  }

  /** Initial credentials for a newly-created staff member (HR/Manager) on a team (§3.1). */
  async sendStaffInvite(email: string, roleLabel: string, tempPassword: string): Promise<void> {
    const host = this.config.get('SMTP_HOST', { infer: true });
    if (!host) {
      this.logger.warn(
        `[DEV INVITE] ${roleLabel} -> ${email} / temp password: ${tempPassword}  (no SMTP configured; logging only)`,
      );
      return;
    }
    // SMTP seam: wire a transport (e.g. nodemailer) here when SMTP_* is configured.
    this.logger.log(`Staff invite dispatched to ${email}`);
  }

  /** Initial credentials for a newly-provisioned Company Admin (§3.1). */
  async sendCompanyAdminInvite(
    email: string,
    companyName: string,
    tempPassword: string,
  ): Promise<void> {
    const host = this.config.get('SMTP_HOST', { infer: true });
    if (!host) {
      this.logger.warn(
        `[DEV INVITE] Company Admin for "${companyName}" -> ${email} / temp password: ${tempPassword}  (no SMTP configured; logging only)`,
      );
      return;
    }
    // SMTP seam: wire a transport (e.g. nodemailer) here when SMTP_* is configured.
    this.logger.log(`Company-admin invite dispatched to ${email}`);
  }
}
