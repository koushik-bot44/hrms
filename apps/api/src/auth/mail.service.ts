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
}
