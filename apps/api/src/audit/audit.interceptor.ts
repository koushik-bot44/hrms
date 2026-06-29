import {
  type CallHandler,
  type ExecutionContext,
  Injectable,
  type NestInterceptor,
} from '@nestjs/common';
import type { Request, Response } from 'express';
import { type Observable, tap } from 'rxjs';
import type { Principal } from '../auth/principal';
import { type AuditActor, AuditService, actorFromPrincipal } from './audit.service';

const MUTATING_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

interface AuditableRequest extends Request {
  user?: Principal;
  auditActor?: AuditActor;
}

/**
 * Global interceptor: logs every successful mutating request to the AuditLog (§7) with
 * actor context. The actor comes from `req.user` (authenticated routes) or
 * `req.auditActor` (set by @Public auth handlers, where `req.user` is not populated).
 */
@Injectable()
export class AuditInterceptor implements NestInterceptor {
  constructor(private readonly audit: AuditService) {}

  intercept(context: ExecutionContext, next: CallHandler): Observable<unknown> {
    const req = context.switchToHttp().getRequest<AuditableRequest>();
    if (!MUTATING_METHODS.has(req.method)) {
      return next.handle();
    }

    return next.handle().pipe(
      tap(() => {
        const actor = req.auditActor ?? actorFromPrincipal(req.user);
        const res = context.switchToHttp().getResponse<Response>();
        const routePath = (req.route as { path?: string } | undefined)?.path ?? req.url;
        void this.audit.record({
          ...actor,
          action: `${req.method} ${routePath}`,
          ipAddress: req.ip ?? null,
          metadata: { statusCode: res.statusCode },
        });
      }),
    );
  }
}
