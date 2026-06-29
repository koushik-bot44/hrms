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

  // --- Auth (§6) ---
  // Dev-defaulted so local runs zero-config; production MUST set real secrets
  // (enforced by the superRefine below).
  JWT_SECRET: z.string().default('dev-insecure-access-secret-change-me'),
  REFRESH_SECRET: z.string().default('dev-insecure-refresh-secret-change-me'),
  // Token lifetimes (vercel/jsonwebtoken duration strings, e.g. "15m", "7d").
  ACCESS_TOKEN_TTL: z.string().default('15m'),
  REFRESH_TOKEN_TTL: z.string().default('7d'),
  // Name of the httpOnly refresh cookie set on the API domain.
  REFRESH_COOKIE_NAME: z.string().default('ihrms_refresh'),
  // Employee OTP time-to-live, seconds.
  OTP_TTL: z.coerce.number().int().positive().default(300),

  // Public URL of the web app, used to build links in emails (e.g. the employee login link).
  WEB_APP_URL: z.string().default('http://localhost:3001'),

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
}).superRefine((env, ctx) => {
  if (env.NODE_ENV === 'production') {
    for (const key of ['JWT_SECRET', 'REFRESH_SECRET'] as const) {
      if (env[key].startsWith('dev-insecure-')) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          path: [key],
          message: `${key} must be set to a strong secret in production`,
        });
      }
    }
  }
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
