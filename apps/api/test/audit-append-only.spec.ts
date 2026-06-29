import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { PrismaClient } from '@prisma/client';
import { auditAppendOnlyExtension } from '../src/prisma/audit-append-only.extension';

// Gated on a real local Postgres (migrated). Run with DPP_TEST_DB_URL set.
const DB = process.env.DPP_TEST_DB_URL;
const describeIf = DB ? describe : describe.skip;

const makeGuarded = (base: PrismaClient) => base.$extends(auditAppendOnlyExtension);

describeIf('AuditLog append-only (§7)', () => {
  let base: PrismaClient;
  let db: ReturnType<typeof makeGuarded>;
  let id: string;

  beforeAll(async () => {
    base = new PrismaClient({ datasources: { db: { url: DB as string } } });
    db = makeGuarded(base);
    await base.$connect();
  });

  afterAll(async () => {
    await base?.$disconnect();
  });

  it('allows creating an AuditLog', async () => {
    const row = await db.auditLog.create({
      data: { actorType: 'SYSTEM', action: 'APPEND_ONLY_PROBE', companyId: null },
    });
    expect(row.id).toBeTruthy();
    id = row.id;
  });

  it('throws on update / delete / updateMany / deleteMany / upsert', async () => {
    await expect(db.auditLog.update({ where: { id }, data: { action: 'X' } })).rejects.toThrow(
      /append-only/,
    );
    await expect(db.auditLog.delete({ where: { id } })).rejects.toThrow(/append-only/);
    await expect(db.auditLog.updateMany({ data: { action: 'X' } })).rejects.toThrow(/append-only/);
    await expect(db.auditLog.deleteMany({})).rejects.toThrow(/append-only/);
    await expect(
      db.auditLog.upsert({
        where: { id },
        create: { actorType: 'SYSTEM', action: 'Y' },
        update: { action: 'Y' },
      }),
    ).rejects.toThrow(/append-only/);
  });
});
