import { z } from 'zod';

/**
 * Environment schema. ConfigModule runs `validateEnv` at boot; any failure throws
 * and the process exits (fail-fast). Secrets for later phases are accepted empty.
 */
export const envSchema = z.object({
  NODE_ENV: z.enum(['development', 'test', 'production']).default('development'),
  PORT: z.coerce.number().int().positive().default(3000),

  // Required.
  DATABASE_URL: z.string().min(1, 'DATABASE_URL is required'),

  // Comma-separated origins; empty means no cross-origin access.
  CORS_ORIGINS: z.string().default(''),

  // Placeholders for later phases — empty values accepted.
  JWT_SECRET: z.string().default(''),
  REFRESH_SECRET: z.string().default(''),

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

  OIDC_ISSUER: z.string().default(''),
  OIDC_CLIENT_ID: z.string().default(''),
  OIDC_CLIENT_SECRET: z.string().default(''),

  BGV_API_URL: z.string().default(''),
  BGV_API_KEY: z.string().default(''),
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
