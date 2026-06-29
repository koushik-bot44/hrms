import { Injectable } from '@nestjs/common';
import { Prisma } from '@prisma/client';
import { PrismaService } from '../prisma/prisma.service';

export interface AuditInput {
  action: string;
  actorType?: string; // SYSTEM | OPERATOR | ... (real auth context arrives in Phase 5)
  actorId?: string | null;
  targetType?: string | null;
  targetId?: string | null;
  ipAddress?: string | null;
  metadata?: Record<string, unknown> | null;
}

/** Writes append-only AuditEvent rows. Never updates/deletes (guarded at the client layer). */
@Injectable()
export class AuditService {
  constructor(private readonly prisma: PrismaService) {}

  async record(input: AuditInput): Promise<void> {
    await this.prisma.guarded.auditEvent.create({
      data: {
        action: input.action,
        actorType: input.actorType ?? 'SYSTEM',
        actorId: input.actorId ?? null,
        targetType: input.targetType ?? null,
        targetId: input.targetId ?? null,
        ipAddress: input.ipAddress ?? null,
        metadata: (input.metadata ?? undefined) as Prisma.InputJsonValue | undefined,
      },
    });
  }
}
