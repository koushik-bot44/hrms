import { Prisma } from '@prisma/client';

/** Operations that would mutate or remove existing AuditEvent rows. */
const FORBIDDEN = new Set([
  'update',
  'updateMany',
  'upsert',
  'delete',
  'deleteMany',
]);

/**
 * Enforces the append-only invariant on the AuditEvent ledger at the client layer:
 * any attempt to update/delete/upsert an AuditEvent throws. Only `create`/`createMany`
 * and reads are allowed. Defense-in-depth — no service exposes a mutation path either.
 */
export const auditAppendOnlyExtension = Prisma.defineExtension({
  name: 'audit-append-only',
  query: {
    auditEvent: {
      $allOperations({ operation, args, query }) {
        if (FORBIDDEN.has(operation)) {
          throw new Error(
            `AuditEvent is append-only: operation "${operation}" is forbidden`,
          );
        }
        return query(args);
      },
    },
  },
});
