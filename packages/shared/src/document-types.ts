/**
 * Document-type code registry — the {TYPE} segment of a document's unique ID.
 *
 * Each code maps to a human label and a {@link DocumentClass}. This is the single
 * source of truth for which 3-letter type codes are valid platform-wide.
 */
import { DocumentClass } from './enums';

export const DocumentTypeCode = {
  OFR: 'OFR',
  EXP: 'EXP',
  APT: 'APT',
  APR: 'APR',
  AGR: 'AGR',
  RTR: 'RTR',
  SVC: 'SVC',
  BGV: 'BGV',
} as const;
export type DocumentTypeCode = (typeof DocumentTypeCode)[keyof typeof DocumentTypeCode];

export interface DocumentTypeMeta {
  code: DocumentTypeCode;
  label: string;
  class: DocumentClass;
}

export const DOCUMENT_TYPE_REGISTRY: Record<DocumentTypeCode, DocumentTypeMeta> = {
  OFR: { code: 'OFR', label: 'Offer Letter', class: 'LETTER' },
  EXP: { code: 'EXP', label: 'Experience Letter', class: 'LETTER' },
  APT: { code: 'APT', label: 'Appointment Letter', class: 'LETTER' },
  APR: { code: 'APR', label: 'Appraisal Letter', class: 'LETTER' },
  AGR: { code: 'AGR', label: 'Agreement', class: 'AGREEMENT' },
  RTR: { code: 'RTR', label: 'Relieving Letter', class: 'LETTER' },
  SVC: { code: 'SVC', label: 'Service Certificate', class: 'CERTIFICATE' },
  BGV: { code: 'BGV', label: 'Background Verification', class: 'VERIFICATION' },
};

/** All valid document-type codes, in registry order. */
export const DOCUMENT_TYPE_CODES = Object.keys(
  DOCUMENT_TYPE_REGISTRY,
) as DocumentTypeCode[];

/** Type guard: is `value` a registered document-type code? */
export function isDocumentTypeCode(value: string): value is DocumentTypeCode {
  return Object.prototype.hasOwnProperty.call(DOCUMENT_TYPE_REGISTRY, value);
}

/** Resolves a type code to its metadata, or `undefined` if unknown. */
export function getDocumentType(code: string): DocumentTypeMeta | undefined {
  return isDocumentTypeCode(code) ? DOCUMENT_TYPE_REGISTRY[code] : undefined;
}
