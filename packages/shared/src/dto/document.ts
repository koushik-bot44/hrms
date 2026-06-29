import { z } from 'zod';
import { DocumentStatus, ProvisioningClass } from '../enums';
import { DOCUMENT_TYPE_CODES } from '../document-types';
import { UNIQUE_ID_REGEX } from '../ids';

const enumValues = <T extends Record<string, string>>(e: T) =>
  Object.values(e) as [string, ...string[]];

const typeCodeEnum = z.enum(DOCUMENT_TYPE_CODES as [string, ...string[]]);

/** POST /documents/collected — register a candidate-submitted upload. */
export const CreateCollectedSchema = z.object({
  consultantId: z.string().min(1),
  typeCode: typeCodeEnum,
  mimeType: z.string().min(1).max(255),
  title: z.string().min(1).max(300).optional(),
});
export type CreateCollectedDto = z.infer<typeof CreateCollectedSchema>;

/** POST /documents/issued — create an ISSUED-class draft shell (no storage/ID yet). */
export const CreateIssuedSchema = z.object({
  consultantId: z.string().min(1),
  typeCode: typeCodeEnum,
  title: z.string().min(1).max(300).optional(),
});
export type CreateIssuedDto = z.infer<typeof CreateIssuedSchema>;

/** POST /documents/referenced — register an external check (e.g. BGV). */
export const CreateReferencedSchema = z.object({
  consultantId: z.string().min(1),
  typeCode: typeCodeEnum.optional(),
  providerRef: z.string().min(1).max(300),
  title: z.string().min(1).max(300).optional(),
});
export type CreateReferencedDto = z.infer<typeof CreateReferencedSchema>;

/** POST /documents/:id/transition — move an ISSUED-class doc along the status machine. */
export const TransitionSchema = z.object({
  toStatus: z.enum(enumValues(DocumentStatus)),
  /** Required on →SUPERSEDED: the successor document that replaces this one. */
  successorId: z.string().min(1).optional(),
});
export type TransitionDto = z.infer<typeof TransitionSchema>;

/** Response after creating a COLLECTED doc: the id + a short-lived presigned PUT. */
export const UploadTicketSchema = z.object({
  documentId: z.string(),
  uploadUrl: z.string().url(),
  expiresInSeconds: z.number().int().positive(),
});
export type UploadTicket = z.infer<typeof UploadTicketSchema>;

/**
 * Safe document view. NEVER exposes storageKey/bucket/endpoint — only an opaque,
 * short-lived `downloadUrl` (present when the doc has stored bytes).
 */
export const DocumentResponseSchema = z.object({
  id: z.string(),
  uniqueId: z.string().regex(UNIQUE_ID_REGEX).nullable(),
  provisioningClass: z.enum(enumValues(ProvisioningClass)),
  typeCode: z.string().nullable(),
  status: z.enum(enumValues(DocumentStatus)).nullable(),
  title: z.string().nullable(),
  mimeType: z.string().nullable(),
  sha256: z.string().nullable(),
  providerRef: z.string().nullable(),
  consultantId: z.string(),
  entityId: z.string(),
  supersededById: z.string().nullable(),
  latestVersion: z.number().int().nullable(),
  issuedAt: z.string().nullable(),
  createdAt: z.string(),
  downloadUrl: z.string().url().optional(),
});
export type DocumentResponse = z.infer<typeof DocumentResponseSchema>;
