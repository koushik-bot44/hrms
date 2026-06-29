import {
  CallHandler,
  ExecutionContext,
  Injectable,
  NestInterceptor,
} from '@nestjs/common';
import { from, Observable } from 'rxjs';
import { concatMap } from 'rxjs/operators';
import type { Request, Response } from 'express';
import { AuditService } from './audit.service';

const MUTATING = new Set(['POST', 'PATCH', 'PUT', 'DELETE']);

/**
 * Logs every MUTATING request (POST/PATCH/PUT/DELETE) to AuditEvent after the handler
 * succeeds. Reads are NOT auto-logged — the vault writes explicit VIEW/DOWNLOAD events.
 */
@Injectable()
export class AuditInterceptor implements NestInterceptor {
  constructor(private readonly audit: AuditService) {}

  intercept(context: ExecutionContext, next: CallHandler): Observable<unknown> {
    if (context.getType() !== 'http') {
      return next.handle();
    }
    const req = context.switchToHttp().getRequest<Request>();
    if (!req || !MUTATING.has(req.method)) {
      return next.handle();
    }
    const res = context.switchToHttp().getResponse<Response>();
    const routePath = (req.route as { path?: string } | undefined)?.path ?? req.path ?? req.url;
    const action = `${req.method} ${routePath}`;
    const ipAddress = req.ip ?? null;
    const paramId = (req.params as Record<string, string> | undefined)?.id ?? null;
    const resource = (req.path ?? '').split('/').filter(Boolean)[0];
    const targetType = resource === 'documents' ? 'Document' : (resource ?? null);

    return next.handle().pipe(
      concatMap((body) => {
        const b = body as Record<string, unknown> | null;
        const targetId =
          (b && ((b.id as string) ?? (b.documentId as string))) ?? paramId ?? null;
        return from(
          this.audit
            .record({
              action,
              actorType: 'SYSTEM',
              targetType,
              targetId,
              ipAddress,
              metadata: { statusCode: res.statusCode },
            })
            .then(() => body),
        );
      }),
    );
  }
}
