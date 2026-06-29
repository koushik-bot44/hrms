import { createHash } from 'node:crypto';
import { BadRequestException, ConflictException, NotFoundException } from '@nestjs/common';
import { DocumentType, SectionKey } from '@ihrms/shared';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { OnboardingService } from '../src/onboarding/onboarding.service';
import { AuditService } from '../src/audit/audit.service';
import type { S3Service } from '../src/storage/storage.service';
import { PrismaService } from '../src/prisma/prisma.service';
import type { EmployeePrincipal } from '../src/auth/principal';

const DB = process.env.DPP_TEST_DB_URL;
const describeIf = DB ? describe : describe.skip;

const CODE = 'ZONB';
const EMAIL = 'zonb-';

// Stub storage: deterministic bytes so the confirmed sha256 is predictable; no MinIO.
const FIXED_BYTES = Buffer.from('hello-document-bytes');
const FIXED_SHA = createHash('sha256').update(FIXED_BYTES).digest('hex');
const s3Stub = {
  isConfigured: () => true,
  buildKey: (c: string, e: string, s: string, f: string) => `companies/${c}/employees/${e}/${s}/k-${f}`,
  presignedPutUrl: async () => 'http://storage.local/put',
  presignedGetUrl: async () => 'http://storage.local/get',
  getObjectBytes: async () => FIXED_BYTES,
} as unknown as S3Service;

const ids: Record<string, string> = {};
const principal = (key: string): EmployeePrincipal => ({
  type: 'EMPLOYEE',
  employeeId: ids[key],
  employeeCode: `${CODE}-EMP-00000${key === 'empA' ? '1' : '2'}`,
  email: `${EMAIL}${key}@t.local`,
  companyId: ids.company,
});

const PERSONAL = { fullName: 'Alex Doe', dateOfBirth: '1990-01-01', phone: '5551234', addressLine: '1 Main St', city: 'Metropolis' };
const GOVERNMENT = { panNumber: 'ABCDE1234F' };

describeIf('OnboardingService (§3.2)', () => {
  let prisma: PrismaService;
  let service: OnboardingService;

  beforeAll(async () => {
    process.env.DATABASE_URL = DB as string;
    prisma = new PrismaService();
    await prisma.$connect();
    service = new OnboardingService(prisma, new AuditService(prisma), s3Stub);
    await cleanup(prisma);

    ids.company = (await prisma.guarded.company.create({ data: { name: 'Onb Co', code: CODE } })).id;
    ids.hr = (
      await prisma.guarded.user.create({
        data: { email: `${EMAIL}hr@t.local`, name: 'HR', role: 'HR', companyId: ids.company },
      })
    ).id;
    const mkEmp = async (n: string) =>
      (
        await prisma.guarded.employee.create({
          data: {
            employeeCode: `${CODE}-EMP-00000${n}`,
            email: `${EMAIL}emp${n}@t.local`,
            companyId: ids.company,
            onboardingHrId: ids.hr,
          },
          select: { id: true },
        })
      ).id;
    ids.empA = await mkEmp('1');
    ids.empB = await mkEmp('2');
  }, 60_000);

  afterAll(async () => {
    if (prisma) {
      await cleanup(prisma);
      await prisma.$disconnect();
    }
  });

  it('saves a valid section (DRAFT) and flips the employee INVITED -> IN_PROGRESS', async () => {
    const section = await service.saveSection(principal('empA'), SectionKey.PERSONAL, { data: PERSONAL });
    expect(section.key).toBe(SectionKey.PERSONAL);
    expect(section.status).toBe('DRAFT');
    expect((section.data as { fullName: string }).fullName).toBe('Alex Doe');

    const dash = await service.dashboard(principal('empA'));
    expect(dash.status).toBe('IN_PROGRESS');
  });

  it('rejects invalid section data', async () => {
    await expect(
      service.saveSection(principal('empA'), SectionKey.GOVERNMENT, { data: { panNumber: 'nope' } }),
    ).rejects.toThrow(BadRequestException);
  });

  it('requests an upload (PENDING) and confirms it -> sha256 + UPLOADED', async () => {
    const presign = await service.requestUpload(principal('empA'), {
      sectionKey: SectionKey.GOVERNMENT,
      docType: DocumentType.PAN,
      fileName: 'pan.pdf',
      mimeType: 'application/pdf',
      sizeBytes: 1024,
    });
    expect(presign.uploadUrl).toContain('http');
    expect(presign.method).toBe('PUT');
    ids.docA = presign.documentId;

    const pending = await prisma.guarded.document.findUnique({
      where: { id: presign.documentId },
      select: { status: true, storageKey: true },
    });
    expect(pending?.status).toBe('PENDING');
    expect(pending?.storageKey).not.toContain('http'); // a key, not a URL

    const confirmed = await service.confirmUpload(principal('empA'), presign.documentId);
    expect(confirmed.status).toBe('UPLOADED');
    expect(confirmed.sha256).toBe(FIXED_SHA);
    expect('storageKey' in confirmed).toBe(false); // never exposed
  });

  it('issues a short-lived view URL for an own document', async () => {
    const view = await service.documentViewUrl(principal('empA'), ids.docA);
    expect(view.url).toContain('http');
    expect(view.expiresInSeconds).toBeLessThanOrEqual(300);
  });

  it("blocks access to another employee's document (own-record scope)", async () => {
    await expect(service.confirmUpload(principal('empB'), ids.docA)).rejects.toThrow(
      NotFoundException,
    );
    await expect(service.documentViewUrl(principal('empB'), ids.docA)).rejects.toThrow(
      NotFoundException,
    );
  });

  it('gates submit on required items, then flips IN_PROGRESS -> SUBMITTED', async () => {
    // empB has nothing yet -> blocked.
    await expect(service.submit(principal('empB'))).rejects.toThrow(BadRequestException);

    // empA has PERSONAL + a PAN doc; still needs the GOVERNMENT section.
    await expect(service.submit(principal('empA'))).rejects.toThrow(BadRequestException);
    await service.saveSection(principal('empA'), SectionKey.GOVERNMENT, { data: GOVERNMENT });

    const dash = await service.submit(principal('empA'));
    expect(dash.status).toBe('SUBMITTED');
    expect(dash.sections.every((s) => s.status === 'SUBMITTED')).toBe(true);
  });

  it('locks the record after submission', async () => {
    await expect(
      service.saveSection(principal('empA'), SectionKey.PERSONAL, { data: PERSONAL }),
    ).rejects.toThrow(ConflictException);
  });

  it('audits mutations + the document view, scoped to the company', async () => {
    const actions = await prisma.guarded.auditLog.findMany({
      where: { companyId: ids.company, actorId: ids.empA },
      select: { action: true, actorType: true, companyId: true },
    });
    const names = new Set(actions.map((a) => a.action));
    expect(names.has('SECTION_SAVED')).toBe(true);
    expect(names.has('DOCUMENT_UPLOADED')).toBe(true);
    expect(names.has('DOCUMENT_VIEWED')).toBe(true);
    expect(names.has('EMPLOYEE_SUBMITTED')).toBe(true);
    expect(actions.every((a) => a.actorType === 'EMPLOYEE' && a.companyId === ids.company)).toBe(true);
  });
});

async function cleanup(prisma: PrismaService): Promise<void> {
  const where = { employee: { employeeCode: { startsWith: CODE } } };
  await prisma.guarded.document.deleteMany({ where });
  await prisma.guarded.profileSection.deleteMany({ where });
  await prisma.guarded.employee.deleteMany({ where: { employeeCode: { startsWith: CODE } } });
  await prisma.guarded.user.deleteMany({ where: { email: { startsWith: EMAIL } } });
  await prisma.guarded.company.deleteMany({ where: { code: { startsWith: CODE } } });
}
