import { z } from 'zod';

/**
 * Environment schema. ConfigModule runs `validateEnv` at boot; any failure throws and
 * the process exits (fail-fast). Secrets for later phases are accepted empty.
 */
export const envSchema = z.object({
  NODE_ENV: z.enum(['development', 'test', 'production']).default('development'),
  PORT: z.coerce.number().int().positive().default(3000),

  // Required — the app will not boot without it.
  DATABASE_URL: z.string().min(1, 'DATABASE_URL is required'),

  // Comma-separated allowed CORS origins; empty means no cross-origin access.
  CORS_ORIGINS: z.string().default(''),

  // --- Auth (§6) — placeholders, wired in a later phase ---
  JWT_SECRET: z.string().default(''),
  REFRESH_SECRET: z.string().default(''),
  // Employee OTP time-to-live, seconds.
  OTP_TTL: z.coerce.number().int().positive().default(300),

  // --- Onboarding email / SMTP — placeholders ---
  SMTP_HOST: z.string().default(''),
  SMTP_PORT: z.coerce.number().int().positive().default(587),
  SMTP_USER: z.string().default(''),
  SMTP_PASSWORD: z.string().default(''),
  SMTP_FROM: z.string().default(''),

  // --- Object storage for uploads (§6) — placeholders ---
  S3_ENDPOINT: z.string().default(''), // optional override: MinIO/local, AWS/R2 in prod
  S3_REGION: z.string().default('us-east-1'),
  S3_BUCKET: z.string().default(''),
  S3_ACCESS_KEY_ID: z.string().default(''),
  S3_SECRET_ACCESS_KEY: z.string().default(''),
  // path-style addressing (required for MinIO); parsed explicitly to avoid 'false'==truthy.
  S3_FORCE_PATH_STYLE: z
    .string()
    .default('false')
    .transform((v) => v === 'true' || v === '1'),
});

export type Env = z.infer<typeof envSchema>;

export function validateEnv(config: Record<string, unknown>): Env {
  const parsed = envSchema.safeParse(config);
  if (!parsed.success) {
    const issues = parsed.error.issues
      .map((i) => `  - ${i.path.join('.') || '(root)'}: ${i.message}`)
      .join('\n');
    throw new Error(`Invalid environment variables:\n${issues}`);
  }
  return parsed.data;
}
