import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { createHash } from 'node:crypto';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Test } from '@nestjs/testing';
import type { INestApplication } from '@nestjs/common';
import S3rver from 's3rver';
import { AppModule } from '../src/app.module';
import { PrismaService } from '../src/prisma/prisma.service';
import { DocumentsService } from '../src/vault/documents.service';
import { IdentityService } from '../src/identity/identity.service';
import { AuditService } from '../src/audit/audit.service';

// Gated on a real local Postgres (DPP_TEST_DB_URL). s3rver (pure-Node S3) is started here.
const DB = process.env.DPP_TEST_DB_URL;
const describeIf = DB ? describe : describe.skip;
const S3_PORT = 54330;

describeIf('Vault / Identity / Audit (integration)', () => {
  let app: INestApplication;
  let prisma: PrismaService;
  let documents: DocumentsService;
  let identity: IdentityService;
  let audit: AuditService;
  let s3: S3rver;
  let baseUrl: string;
  let entityId: string;
  let consultantId: string;

  beforeAll(async () => {
    s3 = new S3rver({
      port: S3_PORT,
      address: '127.0.0.1',
      silent: true,
      directory: mkdtempSync(join(tmpdir(), 's3rver-')),
    });
    await s3.run();

    process.env.DATABASE_URL = DB as string;
    process.env.NODE_ENV = 'test';
    process.env.S3_ENDPOINT = `http://127.0.0.1:${S3_PORT}`;
    process.env.S3_REGION = 'us-east-1';
    process.env.S3_BUCKET = 'cdpp-test';
    process.env.S3_ACCESS_KEY_ID = 'S3RVER';
    process.env.S3_SECRET_ACCESS_KEY = 'S3RVER';
    process.env.S3_FORCE_PATH_STYLE = 'true';

    const moduleRef = await Test.createTestingModule({ imports: [AppModule] }).compile();
    app = moduleRef.createNestApplication();
    await app.listen(0); // runs onModuleInit: $connect + ensureBucket
    baseUrl = await app.getUrl();

    prisma = app.get(PrismaService);
    documents = app.get(DocumentsService);
    identity = app.get(IdentityService);
    audit = app.get(AuditService);

    await prisma.$executeRawUnsafe(
      'TRUNCATE "audit_events","document_versions","document_sequences","documents","consents","requirements","users","entities" RESTART IDENTITY CASCADE',
    );
    const entity = await prisma.entity.create({ data: { code: 'STELLAR', name: 'Stellar Corp' } });
    entityId = entity.id;
    const user = await prisma.user.create({
      data: { email: 'consultant@stellar.example', fullName: 'Consultant', role: 'EMPLOYEE', entityId },
    });
    consultantId = user.id;
  }, 90_000);

  afterAll(async () => {
    await app?.close();
    await s3?.close();
  });

  it('allocates strictly increasing, collision-free sequences under concurrency', async () => {
    const N = 20;
    const ids = await Promise.all(
      Array.from({ length: N }, () => identity.allocateUniqueId(entityId, 'OFR')),
    );
    for (const id of ids) expect(id).toMatch(/^STELLAR-OFR-\d{4}-\d{6}$/);
    const seqs = ids.map((id) => Number(id.split('-')[3]));
    expect(new Set(seqs).size).toBe(N); // zero duplicates
    expect(Math.max(...seqs) - Math.min(...seqs)).toBe(N - 1); // strictly increasing / contiguous
  }, 30_000);

  it('rejects an illegal status transition', async () => {
    const doc = await documents.createIssued({ consultantId, typeCode: 'OFR' });
    expect(doc.status).toBe('DRAFT');
    // DRAFT → ISSUED skips PENDING_APPROVAL — illegal.
    await expect(documents.transition(doc.id, 'ISSUED')).rejects.toThrow(/Illegal/);
  });

  it('runs the legal ISSUED lifecycle: assigns uniqueId on →ISSUED, requires successor on →SUPERSEDED', async () => {
    const doc = await documents.createIssued({ consultantId, typeCode: 'OFR' });
    expect(doc.uniqueId).toBeNull();

    await documents.transition(doc.id, 'PENDING_APPROVAL');
    const issued = await documents.transition(doc.id, 'ISSUED');
    expect(issued.status).toBe('ISSUED');
    expect(issued.uniqueId).toMatch(/^STELLAR-OFR-\d{4}-\d{6}$/);

    await expect(documents.transition(doc.id, 'SUPERSEDED')).rejects.toThrow(/successor/);
    const successor = await documents.createIssued({ consultantId, typeCode: 'OFR' });
    const superseded = await documents.transition(doc.id, 'SUPERSEDED', successor.id);
    expect(superseded.status).toBe('SUPERSEDED');
    expect(superseded.supersededById).toBe(successor.id);
  });

  it('hashes a collected upload into DocumentVersion v1, then v2 on re-upload (v1 stays queryable)', async () => {
    const ticket = await documents.createCollected({
      consultantId,
      typeCode: 'EXP',
      mimeType: 'application/pdf',
    });

    const v1Bytes = Buffer.from('collected evidence v1');
    const put1 = await fetch(ticket.uploadUrl, { method: 'PUT', body: v1Bytes });
    expect(put1.ok).toBe(true);

    const c1 = await documents.confirmUpload(ticket.documentId);
    const expectV1 = createHash('sha256').update(v1Bytes).digest('hex');
    expect(c1.sha256).toBe(expectV1);
    expect(c1.latestVersion).toBe(1);
    const row1 = await prisma.documentVersion.findFirst({
      where: { documentId: ticket.documentId, version: 1 },
    });
    expect(row1?.sha256).toBe(expectV1);

    const v2Bytes = Buffer.from('a different, corrected document v2');
    await fetch(ticket.uploadUrl, { method: 'PUT', body: v2Bytes });
    const c2 = await documents.confirmUpload(ticket.documentId);
    const expectV2 = createHash('sha256').update(v2Bytes).digest('hex');
    expect(c2.sha256).toBe(expectV2);
    expect(c2.latestVersion).toBe(2);

    // old version row is still present and unchanged
    const stillV1 = await prisma.documentVersion.findFirst({
      where: { documentId: ticket.documentId, version: 1 },
    });
    expect(stillV1?.sha256).toBe(expectV1);
  }, 30_000);

  it('writes an AuditEvent for a mutating request (interceptor) and for a document view (explicit)', async () => {
    const res = await fetch(`${baseUrl}/documents/issued`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ consultantId, typeCode: 'OFR' }),
    });
    expect(res.status).toBe(201);
    const created = (await res.json()) as { id: string };

    const mutationAudit = await prisma.auditEvent.findFirst({
      where: { action: { contains: 'documents/issued' } },
      orderBy: { createdAt: 'desc' },
    });
    expect(mutationAudit).toBeTruthy();
    expect(mutationAudit?.targetType).toBe('Document');

    await documents.getDocument(created.id, '127.0.0.1');
    const viewAudit = await prisma.auditEvent.findFirst({
      where: { action: 'DOCUMENT_VIEW', targetId: created.id },
    });
    expect(viewAudit).toBeTruthy();
  }, 30_000);

  it('forbids updating or deleting an AuditEvent (append-only)', async () => {
    await audit.record({ action: 'APPEND_ONLY_PROBE', targetType: 'Document', targetId: 'probe' });
    const row = await prisma.auditEvent.findFirst({ where: { action: 'APPEND_ONLY_PROBE' } });
    expect(row).toBeTruthy();
    const id = row!.id;

    await expect(
      prisma.guarded.auditEvent.update({ where: { id }, data: { action: 'TAMPERED' } }),
    ).rejects.toThrow(/append-only/);
    await expect(prisma.guarded.auditEvent.delete({ where: { id } })).rejects.toThrow(/append-only/);
    await expect(prisma.guarded.auditEvent.deleteMany({})).rejects.toThrow(/append-only/);
    await expect(
      prisma.guarded.auditEvent.updateMany({ data: { action: 'x' } }),
    ).rejects.toThrow(/append-only/);
    await expect(
      prisma.guarded.auditEvent.upsert({ where: { id }, create: {} as never, update: { action: 'x' } }),
    ).rejects.toThrow(/append-only/);
  });
});
