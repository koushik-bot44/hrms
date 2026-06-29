import { z } from 'zod';
import { ENTITY_CODE_REGEX } from '../ids';

/**
 * Request contract for creating an Entity (employer/organization).
 * `code` becomes the {ENTITY} segment of every document's unique ID.
 */
export const CreateEntitySchema = z.object({
  code: z
    .string()
    .regex(ENTITY_CODE_REGEX, 'code must be uppercase alphanumeric, 2-16 chars, start with a letter'),
  name: z.string().min(1).max(200),
});

export type CreateEntityDto = z.infer<typeof CreateEntitySchema>;
