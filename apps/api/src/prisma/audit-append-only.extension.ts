import { Prisma } from '@prisma/client';

/** Operations that would mutate or remove existing AuditLog rows. */
const FORBIDDEN = new Set([
  'update',
  'updateMany',
  'upsert',
  'delete',
  'deleteMany',
]);

/**
 * Enforces the append-only invariant on the AuditLog ledger (§7) at the client layer:
 * any attempt to update/delete/upsert an AuditLog throws. Only `create`/`createMany`
 * and reads are allowed. Defense-in-depth — no service exposes a mutation path either.
 */
export const auditAppendOnlyExtension = Prisma.defineExtension({
  name: 'audit-append-only',
  query: {
    auditLog: {
      $allOperations({ operation, args, query }) {
        if (FORBIDDEN.has(operation)) {
          throw new Error(
            `AuditLog is append-only: operation "${operation}" is forbidden`,
          );
        }
        return query(args);
      },
    },
  },
});
