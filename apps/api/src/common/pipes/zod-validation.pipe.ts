import { BadRequestException, type PipeTransform } from '@nestjs/common';
import type { ZodType } from 'zod';

/**
 * Validates a request payload against a zod schema from @ihrms/shared. The global
 * class-validator ValidationPipe skips plain-object bodies (no class metadata), so this
 * pipe is applied per-handler: `@Body(new ZodValidationPipe(StaffLoginSchema))`.
 */
export class ZodValidationPipe<T> implements PipeTransform {
  constructor(private readonly schema: ZodType<T>) {}

  transform(value: unknown): T {
    const result = this.schema.safeParse(value);
    if (!result.success) {
      throw new BadRequestException({
        message: result.error.issues.map(
          (i) => `${i.path.join('.') || '(body)'}: ${i.message}`,
        ),
        error: 'Bad Request',
        statusCode: 400,
      });
    }
    return result.data;
  }
}
