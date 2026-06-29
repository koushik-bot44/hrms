import { Injectable, Logger } from '@nestjs/common';
import { PrismaService } from '../prisma/prisma.service';
import type { Principal } from '../auth/principal';

export type ActorType = 'USER' | 'EMPLOYEE' | 'SYSTEM';

/** Resolved actor context for an audit row. Auth handlers set this on `req.auditActor`. */
export interface AuditActor {
  actorType: ActorType;
  actorId?: string | null;
  companyId?: string | null;
}

export interface AuditEntry extends AuditActor {
  action: string;
  targetType?: string | null;
  targetId?: string | null;
  metadata?: Record<string, unknown> | null;
  ipAddress?: string | null;
}

/** Derive the audit actor from an authenticated principal (or SYSTEM when absent). */
export function actorFromPrincipal(principal?: Principal): AuditActor {
  if (!principal) {
    return { actorType: 'SYSTEM' };
  }
  if (principal.type === 'USER') {
    return { actorType: 'USER', actorId: principal.userId, companyId: principal.companyId };
  }
  return { actorType: 'EMPLOYEE', actorId: principal.employeeId, companyId: principal.companyId };
}

/**
 * Writes append-only AuditLog rows (§7) via the guarded client. Audit failures are
 * logged but never propagated — a transient audit error must not fail the user's action.
 */
@Injectable()
export class AuditService {
  private readonly logger = new Logger(AuditService.name);

  constructor(private readonly prisma: PrismaService) {}

  async record(entry: AuditEntry): Promise<void> {
    try {
      await this.prisma.guarded.auditLog.create({
        data: {
          companyId: entry.companyId ?? null,
          actorType: entry.actorType,
          actorId: entry.actorId ?? null,
          action: entry.action,
          targetType: entry.targetType ?? null,
          targetId: entry.targetId ?? null,
          metadata: entry.metadata ?? undefined,
          ipAddress: entry.ipAddress ?? null,
        },
      });
    } catch (err) {
      this.logger.error(
        `Failed to write audit log for "${entry.action}"`,
        err instanceof Error ? err.stack : String(err),
      );
    }
  }
}
