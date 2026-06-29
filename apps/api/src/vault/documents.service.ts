import { createHash } from 'node:crypto';
import {
  BadRequestException,
  Injectable,
  NotFoundException,
  UnprocessableEntityException,
} from '@nestjs/common';
import { Prisma } from '@prisma/client';
import type { Document } from '@prisma/client';
import {
  DocumentStatus,
  ProvisioningClass,
  getDocumentType,
  type CreateCollectedDto,
  type CreateIssuedDto,
  type CreateReferencedDto,
  type DocumentResponse,
  type UploadTicket,
} from '@cdpp/shared';
import { PrismaService } from '../prisma/prisma.service';
import { PRESIGNED_TTL_SECONDS, StorageService } from '../storage/storage.service';
import { IdentityService } from '../identity/identity.service';
import { AuditService } from '../audit/audit.service';

/** Legal status edges for the ISSUED-class status machine. */
const LEGAL_EDGES: Record<string, DocumentStatus[]> = {
  [DocumentStatus.DRAFT]: [DocumentStatus.PENDING_APPROVAL],
  [DocumentStatus.PENDING_APPROVAL]: [DocumentStatus.ISSUED],
  [DocumentStatus.ISSUED]: [DocumentStatus.SUPERSEDED, DocumentStatus.REVOKED],
  [DocumentStatus.SUPERSEDED]: [],
  [DocumentStatus.REVOKED]: [],
};

/** Typed error for rejected status transitions. */
export class IllegalTransitionError extends UnprocessableEntityException {
  constructor(from: string | null, to: string) {
    super(`Illegal document transition: ${from ?? '∅'} → ${to}`);
  }
}

@Injectable()
export class DocumentsService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly storage: StorageService,
    private readonly identity: IdentityService,
    private readonly audit: AuditService,
  ) {}

  // --- creation (one per provisioning class) ------------------------------

  /** COLLECTED: candidate evidence. Creates a shell + returns a presigned PUT. No uniqueId. */
  async createCollected(dto: CreateCollectedDto): Promise<UploadTicket> {
    const consultant = await this.requireConsultant(dto.consultantId);
    const doc = await this.prisma.guarded.document.create({
      data: {
        provisioningClass: ProvisioningClass.COLLECTED,
        typeCode: dto.typeCode,
        mimeType: dto.mimeType,
        title: dto.title ?? null,
        ownerId: consultant.id,
        entityId: consultant.entityId,
      },
    });
    const stagingKey = this.stagingKey(consultant.entityId, doc.id);
    await this.prisma.guarded.document.update({
      where: { id: doc.id },
      data: { storageKey: stagingKey },
    });
    const uploadUrl = await this.storage.getUploadUrl(stagingKey);
    return { documentId: doc.id, uploadUrl, expiresInSeconds: PRESIGNED_TTL_SECONDS };
  }

  /** ISSUED: company-authored draft shell. Merge/PDF/storage + uniqueId happen on →ISSUED (Phase 7). */
  async createIssued(dto: CreateIssuedDto): Promise<DocumentResponse> {
    const consultant = await this.requireConsultant(dto.consultantId);
    const meta = getDocumentType(dto.typeCode);
    if (!meta || meta.provisioningClass !== ProvisioningClass.ISSUED) {
      throw new BadRequestException(`Type code "${dto.typeCode}" is not an ISSUED-class code`);
    }
    const doc = await this.prisma.guarded.document.create({
      data: {
        provisioningClass: ProvisioningClass.ISSUED,
        typeCode: dto.typeCode,
        class: meta.class,
        status: DocumentStatus.DRAFT,
        title: dto.title ?? null,
        ownerId: consultant.id,
        entityId: consultant.entityId,
      },
    });
    return this.toResponse(doc);
  }

  /** REFERENCED: external check (e.g. BGV). providerRef set; report storage populated in Phase 8. No uniqueId. */
  async createReferenced(dto: CreateReferencedDto): Promise<DocumentResponse> {
    const consultant = await this.requireConsultant(dto.consultantId);
    const doc = await this.prisma.guarded.document.create({
      data: {
        provisioningClass: ProvisioningClass.REFERENCED,
        typeCode: dto.typeCode ?? null,
        providerRef: dto.providerRef,
        title: dto.title ?? null,
        ownerId: consultant.id,
        entityId: consultant.entityId,
      },
    });
    return this.toResponse(doc);
  }

  // --- collected upload confirmation --------------------------------------

  /** Hash the uploaded object, append the next DocumentVersion, mirror sha256 onto the doc. */
  async confirmUpload(documentId: string): Promise<DocumentResponse> {
    const doc = await this.requireDocument(documentId);
    if (!doc.storageKey) {
      throw new BadRequestException('Document has no pending upload');
    }
    // Always read the freshly-uploaded bytes from the stable staging key (NOT the
    // moving `storageKey`, which points at the latest confirmed version).
    const stagingKey = this.stagingKey(doc.entityId, doc.id);
    const bytes = await this.storage.getObjectBytes(stagingKey);
    const sha256 = createHash('sha256').update(bytes).digest('hex');

    const existing = await this.prisma.guarded.documentVersion.count({ where: { documentId } });
    const version = existing + 1;
    const versionKey = `collected/${doc.entityId}/${doc.id}/v${version}`;
    // Snapshot the staged object under a per-version key so old versions stay retrievable.
    await this.storage.copyObject(stagingKey, versionKey);

    await this.prisma.guarded.documentVersion.create({
      data: { documentId, version, sha256, mimeType: doc.mimeType, storageKey: versionKey },
    });
    const updated = await this.prisma.guarded.document.update({
      where: { id: documentId },
      data: { sha256, storageKey: versionKey },
    });
    return this.toResponse(updated, version);
  }

  // --- audited read -------------------------------------------------------

  /** Returns metadata + a short-lived presigned download URL, and writes a VIEW audit event. */
  async getDocument(documentId: string, ip?: string | null): Promise<DocumentResponse> {
    const doc = await this.requireDocument(documentId);
    const response = this.toResponse(doc, await this.latestVersion(documentId));
    if (doc.storageKey) {
      response.downloadUrl = await this.storage.getDownloadUrl(doc.storageKey);
    }
    await this.audit.record({
      action: 'DOCUMENT_VIEW',
      actorType: 'SYSTEM',
      targetType: 'Document',
      targetId: documentId,
      ipAddress: ip ?? null,
      metadata: { downloadUrlIssued: Boolean(doc.storageKey) },
    });
    return response;
  }

  // --- status machine (ISSUED-class only) ---------------------------------

  async transition(
    documentId: string,
    toStatus: string,
    successorId?: string,
  ): Promise<DocumentResponse> {
    const doc = await this.requireDocument(documentId);
    if (doc.provisioningClass !== ProvisioningClass.ISSUED) {
      throw new BadRequestException(
        'Status transitions apply to ISSUED-class documents only',
      );
    }
    const from = doc.status;
    const allowed = LEGAL_EDGES[from ?? ''] ?? [];
    if (!allowed.includes(toStatus as DocumentStatus)) {
      throw new IllegalTransitionError(from, toStatus);
    }

    const data: Prisma.DocumentUpdateInput = { status: toStatus as DocumentStatus };

    if (toStatus === DocumentStatus.ISSUED) {
      if (!doc.uniqueId) {
        if (!doc.typeCode) {
          throw new BadRequestException('Issued document is missing a type code');
        }
        data.uniqueId = await this.identity.allocateUniqueId(doc.entityId, doc.typeCode);
      }
      data.issuedAt = new Date();
    }

    if (toStatus === DocumentStatus.SUPERSEDED) {
      if (!successorId) {
        throw new BadRequestException('A successor document is required to supersede');
      }
      const successor = await this.prisma.guarded.document.findUnique({
        where: { id: successorId },
      });
      if (!successor) {
        throw new NotFoundException(`Successor "${successorId}" not found`);
      }
      data.supersededBy = { connect: { id: successorId } };
    }

    const updated = await this.prisma.guarded.document.update({ where: { id: documentId }, data });
    return this.toResponse(updated, await this.latestVersion(documentId));
  }

  // --- helpers ------------------------------------------------------------

  /** Stable per-document key the client uploads to (overwritten on each re-upload). */
  private stagingKey(entityId: string, documentId: string): string {
    return `collected/${entityId}/${documentId}/staging`;
  }

  private async requireConsultant(consultantId: string) {
    const consultant = await this.prisma.guarded.user.findUnique({ where: { id: consultantId } });
    if (!consultant) {
      throw new NotFoundException(`Consultant "${consultantId}" not found`);
    }
    return consultant;
  }

  private async requireDocument(documentId: string): Promise<Document> {
    const doc = await this.prisma.guarded.document.findUnique({ where: { id: documentId } });
    if (!doc) {
      throw new NotFoundException(`Document "${documentId}" not found`);
    }
    return doc;
  }

  private async latestVersion(documentId: string): Promise<number | null> {
    const v = await this.prisma.guarded.documentVersion.findFirst({
      where: { documentId },
      orderBy: { version: 'desc' },
      select: { version: true },
    });
    return v?.version ?? null;
  }

  /** Safe projection — NEVER exposes storageKey/bucket/endpoint. */
  private toResponse(doc: Document, latestVersion: number | null = null): DocumentResponse {
    return {
      id: doc.id,
      uniqueId: doc.uniqueId,
      provisioningClass: doc.provisioningClass,
      typeCode: doc.typeCode,
      status: doc.status,
      title: doc.title,
      mimeType: doc.mimeType,
      sha256: doc.sha256,
      providerRef: doc.providerRef,
      consultantId: doc.ownerId,
      entityId: doc.entityId,
      supersededById: doc.supersededById,
      latestVersion,
      issuedAt: doc.issuedAt ? doc.issuedAt.toISOString() : null,
      createdAt: doc.createdAt.toISOString(),
    };
  }
}
