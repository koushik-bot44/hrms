import { z } from 'zod';

/**
 * Response contract for `GET /health`.
 * `status` is always `"ok"` when the process is up; `db` reflects DB reachability.
 */
export const HealthResponseSchema = z.object({
  status: z.literal('ok'),
  db: z.enum(['up', 'down']),
});

export type HealthResponse = z.infer<typeof HealthResponseSchema>;
