import { z } from 'zod';

/**
 * Environment schema. ConfigModule runs `validateEnv` at boot; any failure throws
 * and the process exits (fail-fast). Deployment essentials only — add new project
 * variables here as they arrive.
 */
export const envSchema = z.object({
  NODE_ENV: z.enum(['development', 'test', 'production']).default('development'),
  PORT: z.coerce.number().int().positive().default(3000),

  // Required — the app will not boot without it.
  DATABASE_URL: z.string().min(1, 'DATABASE_URL is required'),

  // Comma-separated allowed CORS origins; empty means no cross-origin access.
  CORS_ORIGINS: z.string().default(''),
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
