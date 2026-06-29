import { randomUUID } from 'node:crypto';
import { BadRequestException, Injectable, NotFoundException } from '@nestjs/common';
import { Prisma } from '@prisma/client';
import { formatUniqueId, isIssuedClassCode, type DocumentTypeCode } from '@cdpp/shared';
import { PrismaService } from '../prisma/prisma.service';

/**
 * Allocates `{ENTITY}-{TYPE}-{YYYY}-{NNNNNN}` unique IDs. The ID format, registry,
 * and validation all come from @cdpp/shared — never re-implemented here.
 */
@Injectable()
export class IdentityService {
  constructor(private readonly prisma: PrismaService) {}

  /**
   * Atomically allocate the next unique ID for an entity + ISSUED-class type code.
   *
   * Concurrency-safe: a single `INSERT … ON CONFLICT DO UPDATE … RETURNING` row-locks
   * the (entity,type,year) sequence row, so parallel callers get strictly increasing,
   * collision-free sequence numbers.
   */
  async allocateUniqueId(entityId: string, typeCode: string): Promise<string> {
    if (!isIssuedClassCode(typeCode)) {
      throw new BadRequestException(
        `Type code "${typeCode}" is not an issuable (ISSUED-class) code`,
      );
    }

    const entity = await this.prisma.guarded.entity.findUnique({ where: { id: entityId } });
    if (!entity) {
      throw new NotFoundException(`Entity "${entityId}" not found`);
    }

    const year = new Date().getUTCFullYear();

    const sequence = await this.prisma.guarded.$transaction(async (tx) => {
      const rows = await tx.$queryRaw<Array<{ lastSeq: number }>>(Prisma.sql`
        INSERT INTO "document_sequences" ("id", "entityId", "typeCode", "year", "lastSeq", "createdAt", "updatedAt")
        VALUES (${randomUUID()}, ${entityId}, ${typeCode}, ${year}, 1, now(), now())
        ON CONFLICT ("entityId", "typeCode", "year")
        DO UPDATE SET "lastSeq" = "document_sequences"."lastSeq" + 1, "updatedAt" = now()
        RETURNING "lastSeq"
      `);
      return Number(rows[0].lastSeq);
    });

    // Shared formatter validates entity code, type code, year, and sequence bounds.
    // typeCode is a known ISSUED-class code here (checked above), so the cast is safe.
    return formatUniqueId({
      entityCode: entity.code,
      typeCode: typeCode as DocumentTypeCode,
      year,
      sequence,
    });
  }
}
