/**
 * Document-type code registry — the {TYPE} segment of a document's unique ID.
 *
 * Each code maps to a human label and a {@link DocumentClass}. This is the single
 * source of truth for which 3-letter type codes are valid platform-wide.
 */
import { DocumentClass, ProvisioningClass } from './enums';

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
  /** Document-type category (letter/agreement/…). */
  class: DocumentClass;
  /** How a document of this type enters the system. Only ISSUED codes get a unique ID. */
  provisioningClass: ProvisioningClass;
}

export const DOCUMENT_TYPE_REGISTRY: Record<DocumentTypeCode, DocumentTypeMeta> = {
  OFR: { code: 'OFR', label: 'Offer Letter', class: 'LETTER', provisioningClass: 'ISSUED' },
  EXP: { code: 'EXP', label: 'Experience Letter', class: 'LETTER', provisioningClass: 'ISSUED' },
  APT: { code: 'APT', label: 'Appointment Letter', class: 'LETTER', provisioningClass: 'ISSUED' },
  APR: { code: 'APR', label: 'Appraisal Letter', class: 'LETTER', provisioningClass: 'ISSUED' },
  AGR: { code: 'AGR', label: 'Agreement', class: 'AGREEMENT', provisioningClass: 'ISSUED' },
  RTR: { code: 'RTR', label: 'Relieving Letter', class: 'LETTER', provisioningClass: 'ISSUED' },
  SVC: { code: 'SVC', label: 'Service Certificate', class: 'CERTIFICATE', provisioningClass: 'ISSUED' },
  // BGV is an external check — REFERENCED, never gets a unique ID.
  BGV: { code: 'BGV', label: 'Background Verification', class: 'VERIFICATION', provisioningClass: 'REFERENCED' },
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

/** The provisioning class for a registered code, or `undefined` if unknown. */
export function getProvisioningClass(code: string): ProvisioningClass | undefined {
  return getDocumentType(code)?.provisioningClass;
}

/**
 * True only for known, ISSUED-class type codes — i.e. codes that receive a unique
 * ID via the status machine. Unknown codes and non-ISSUED codes (e.g. BGV) are false.
 */
export function isIssuedClassCode(code: string): boolean {
  return getProvisioningClass(code) === ProvisioningClass.ISSUED;
}
