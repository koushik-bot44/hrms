import { createHash } from 'node:crypto';
import {
  BadRequestException,
  ConflictException,
  Injectable,
  NotFoundException,
} from '@nestjs/common';
import {
  SECTION_DATA_SCHEMAS,
  evaluateSubmission,
  type DocumentDto,
  type OnboardingDashboard,
  type PresignedUpload,
  type PresignedView,
  type ProfileSectionDto,
  type DocumentUploadRequestInput,
  type SaveSectionInput,
  type SectionKey,
} from '@ihrms/shared';
import { PrismaService } from '../prisma/prisma.service';
import { AuditService } from '../audit/audit.service';
import { S3Service } from '../storage/storage.service';
import type { EmployeePrincipal } from '../auth/principal';

const UPLOAD_TTL_SECONDS = 300; // presigned PUT
const VIEW_TTL_SECONDS = 120; // presigned GET (short-lived)

// Statuses where the employee may still edit their record (before submission).
const EDITABLE_STATUSES = new Set(['INVITED', 'IN_PROGRESS', 'REJECTED']);

interface EmployeeRow {
  id: string;
  employeeCode: string;
  email: string;
  status: string;
  companyId: string;
}

interface SectionRow {
  key: string;
  data: unknown;
  status: string;
  updatedAt: Date;
}

interface DocumentRow {
  id: string;
  sectionKey: string;
  docType: string;
  fileName: string;
  mimeType: string;
  sha256: string | null;
  status: string;
  uploadedAt: Date;
}

function toSectionDto(section: SectionRow): ProfileSectionDto {
  return {
    key: section.key as ProfileSectionDto['key'],
    data: (section.data ?? {}) as Record<string, unknown>,
    status: section.status as ProfileSectionDto['status'],
    updatedAt: section.updatedAt.toISOString(),
  };
}

function toDocumentDto(doc: DocumentRow): DocumentDto {
  return {
    id: doc.id,
    sectionKey: doc.sectionKey as DocumentDto['sectionKey'],
    docType: doc.docType as DocumentDto['docType'],
    fileName: doc.fileName,
    mimeType: doc.mimeType,
    sha256: doc.sha256,
    status: doc.status as DocumentDto['status'],
    uploadedAt: doc.uploadedAt.toISOString(),
  };
}

/**
 * Employee self-service onboarding (§3.2). Every operation is scoped to the calling
 * employee's own record (employeeId comes from the principal; document ops re-check
 * ownership), and every mutation + document view is audited.
 */
@Injectable()
export class OnboardingService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly audit: AuditService,
    private readonly s3: S3Service,
  ) {}

  async dashboard(emp: EmployeePrincipal): Promise<OnboardingDashboard> {
    const employee = await this.loadEmployee(emp.employeeId);
    const [sections, documents] = await Promise.all([
      this.prisma.guarded.profileSection.findMany({
        where: { employeeId: emp.employeeId },
        orderBy: { key: 'asc' },
        select: { key: true, data: true, status: true, updatedAt: true },
      }),
      this.prisma.guarded.document.findMany({
        where: { employeeId: emp.employeeId },
        orderBy: { uploadedAt: 'desc' },
        select: {
          id: true,
          sectionKey: true,
          docType: true,
          fileName: true,
          mimeType: true,
          sha256: true,
          status: true,
          uploadedAt: true,
        },
      }),
    ]);
    return {
      employeeCode: employee.employeeCode,
      email: employee.email,
      status: employee.status as OnboardingDashboard['status'],
      sections: sections.map(toSectionDto),
      documents: documents.map(toDocumentDto),
    };
  }

  async saveSection(
    emp: EmployeePrincipal,
    key: string,
    body: SaveSectionInput,
    ip?: string,
  ): Promise<ProfileSectionDto> {
    const schema = SECTION_DATA_SCHEMAS[key as SectionKey];
    if (!schema) {
      throw new BadRequestException(`Unknown section "${key}"`);
    }
    const parsed = schema.safeParse(body.data);
    if (!parsed.success) {
      throw new BadRequestException({
        message: parsed.error.issues.map((i) => `${i.path.join('.') || '(field)'}: ${i.message}`),
        error: 'Bad Request',
        statusCode: 400,
      });
    }

    const employee = await this.loadEmployee(emp.employeeId);
    this.assertEditable(employee.status);

    const section = await this.prisma.guarded.profileSection.upsert({
      where: { employeeId_key: { employeeId: emp.employeeId, key: key as SectionKey } },
      create: { employeeId: emp.employeeId, key: key as SectionKey, data: parsed.data, status: 'DRAFT' },
      update: { data: parsed.data, status: 'DRAFT' },
      select: { key: true, data: true, status: true, updatedAt: true, id: true },
    });
    await this.markInProgress(employee);

    await this.audit.record({
      actorType: 'EMPLOYEE',
      actorId: emp.employeeId,
      companyId: emp.companyId,
      action: 'SECTION_SAVED',
      targetType: 'ProfileSection',
      targetId: section.id,
      metadata: { key },
      ipAddress: ip ?? null,
    });
    return toSectionDto(section);
  }

  async requestUpload(
    emp: EmployeePrincipal,
    body: DocumentUploadRequestInput,
    ip?: string,
  ): Promise<PresignedUpload> {
    const employee = await this.loadEmployee(emp.employeeId);
    this.assertEditable(employee.status);

    const storageKey = this.s3.buildKey(
      emp.companyId,
      emp.employeeId,
      body.sectionKey,
      body.fileName,
    );
    const doc = await this.prisma.guarded.document.create({
      data: {
        employeeId: emp.employeeId,
        sectionKey: body.sectionKey,
        docType: body.docType,
        fileName: body.fileName,
        storageKey,
        mimeType: body.mimeType,
        status: 'PENDING',
      },
      select: { id: true },
    });
    const uploadUrl = await this.s3.presignedPutUrl(storageKey, body.mimeType, UPLOAD_TTL_SECONDS);
    await this.markInProgress(employee);

    await this.audit.record({
      actorType: 'EMPLOYEE',
      actorId: emp.employeeId,
      companyId: emp.companyId,
      action: 'DOCUMENT_UPLOAD_REQUESTED',
      targetType: 'Document',
      targetId: doc.id,
      metadata: { sectionKey: body.sectionKey, docType: body.docType, fileName: body.fileName },
      ipAddress: ip ?? null,
    });

    return {
      documentId: doc.id,
      uploadUrl,
      method: 'PUT',
      headers: { 'Content-Type': body.mimeType },
      expiresInSeconds: UPLOAD_TTL_SECONDS,
    };
  }

  async confirmUpload(
    emp: EmployeePrincipal,
    documentId: string,
    ip?: string,
  ): Promise<DocumentDto> {
    const doc = await this.loadOwnDocument(emp.employeeId, documentId);
    const employee = await this.loadEmployee(emp.employeeId);
    this.assertEditable(employee.status);

    const bytes = await this.s3.getObjectBytes(doc.storageKey);
    const sha256 = createHash('sha256').update(bytes).digest('hex');

    const updated = await this.prisma.guarded.document.update({
      where: { id: documentId },
      data: { sha256, status: 'UPLOADED' },
      select: {
        id: true,
        sectionKey: true,
        docType: true,
        fileName: true,
        mimeType: true,
        sha256: true,
        status: true,
        uploadedAt: true,
      },
    });

    await this.audit.record({
      actorType: 'EMPLOYEE',
      actorId: emp.employeeId,
      companyId: emp.companyId,
      action: 'DOCUMENT_UPLOADED',
      targetType: 'Document',
      targetId: documentId,
      metadata: { docType: doc.docType, sha256, sizeBytes: bytes.length },
      ipAddress: ip ?? null,
    });
    return toDocumentDto(updated);
  }

  /** Short-lived presigned GET for viewing/downloading a document — a sensitive read, audited. */
  async documentViewUrl(
    emp: EmployeePrincipal,
    documentId: string,
    ip?: string,
  ): Promise<PresignedView> {
    const doc = await this.loadOwnDocument(emp.employeeId, documentId);
    const url = await this.s3.presignedGetUrl(doc.storageKey, VIEW_TTL_SECONDS);
    await this.audit.record({
      actorType: 'EMPLOYEE',
      actorId: emp.employeeId,
      companyId: emp.companyId,
      action: 'DOCUMENT_VIEWED',
      targetType: 'Document',
      targetId: documentId,
      metadata: { docType: doc.docType },
      ipAddress: ip ?? null,
    });
    return { url, expiresInSeconds: VIEW_TTL_SECONDS };
  }

  async submit(emp: EmployeePrincipal, ip?: string): Promise<OnboardingDashboard> {
    const employee = await this.loadEmployee(emp.employeeId);
    if (!EDITABLE_STATUSES.has(employee.status)) {
      throw new ConflictException(`Cannot submit from status ${employee.status}`);
    }

    const [sections, documents] = await Promise.all([
      this.prisma.guarded.profileSection.findMany({
        where: { employeeId: emp.employeeId },
        select: { key: true },
      }),
      this.prisma.guarded.document.findMany({
        where: { employeeId: emp.employeeId },
        select: { sectionKey: true, docType: true, status: true },
      }),
    ]);

    const result = evaluateSubmission(
      sections.map((s) => ({ key: s.key as SectionKey })),
      documents.map((d) => ({
        sectionKey: d.sectionKey as DocumentDto['sectionKey'],
        docType: d.docType as DocumentDto['docType'],
        status: d.status as DocumentDto['status'],
      })),
    );
    if (!result.complete) {
      throw new BadRequestException({
        message: 'Complete all required sections and documents before submitting',
        missingSections: result.missingSections,
        missingDocuments: result.missingDocuments,
        error: 'Bad Request',
        statusCode: 400,
      });
    }

    await this.prisma.guarded.$transaction([
      this.prisma.guarded.profileSection.updateMany({
        where: { employeeId: emp.employeeId },
        data: { status: 'SUBMITTED' },
      }),
      this.prisma.guarded.employee.update({
        where: { id: emp.employeeId },
        data: { status: 'SUBMITTED' },
      }),
    ]);

    await this.audit.record({
      actorType: 'EMPLOYEE',
      actorId: emp.employeeId,
      companyId: emp.companyId,
      action: 'EMPLOYEE_SUBMITTED',
      targetType: 'Employee',
      targetId: emp.employeeId,
      ipAddress: ip ?? null,
    });

    return this.dashboard(emp);
  }

  private async loadEmployee(id: string): Promise<EmployeeRow> {
    const employee = await this.prisma.guarded.employee.findUnique({
      where: { id },
      select: { id: true, employeeCode: true, email: true, status: true, companyId: true },
    });
    if (!employee) {
      throw new NotFoundException('Employee not found');
    }
    return employee;
  }

  /** Loads a document only if it belongs to this employee (own-record scope). */
  private async loadOwnDocument(
    employeeId: string,
    documentId: string,
  ): Promise<{ id: string; storageKey: string; docType: string }> {
    const doc = await this.prisma.guarded.document.findFirst({
      where: { id: documentId, employeeId },
      select: { id: true, storageKey: true, docType: true },
    });
    if (!doc) {
      throw new NotFoundException('Document not found');
    }
    return doc;
  }

  private assertEditable(status: string): void {
    if (!EDITABLE_STATUSES.has(status)) {
      throw new ConflictException('Your record is locked for verification');
    }
  }

  private async markInProgress(employee: EmployeeRow): Promise<void> {
    if (employee.status === 'INVITED') {
      await this.prisma.guarded.employee.update({
        where: { id: employee.id },
        data: { status: 'IN_PROGRESS' },
      });
    }
  }
}
