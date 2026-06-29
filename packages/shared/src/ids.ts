/**
 * Unique-ID format helpers.
 *
 * Format: `{ENTITY}-{TYPE}-{YYYY}-{NNNNNN}`
 *   ENTITY  uppercase alphanumeric entity code (e.g. "NAME"), 2..16 chars, starts with a letter
 *   TYPE    one of the registered 3-letter {@link DocumentTypeCode}s (e.g. "OFR")
 *   YYYY    4-digit year
 *   NNNNNN  6-digit zero-padded sequence (000001..999999), unique per (entity, type, year)
 *
 * Example: `NAME-OFR-2026-000042`
 */
import { isDocumentTypeCode, DocumentTypeCode } from './document-types';

export const ENTITY_CODE_REGEX = /^[A-Z][A-Z0-9]{1,15}$/;
export const UNIQUE_ID_REGEX = /^[A-Z][A-Z0-9]{1,15}-[A-Z]{3}-\d{4}-\d{6}$/;

export const SEQUENCE_MIN = 1;
export const SEQUENCE_MAX = 999_999;

export interface UniqueIdParts {
  entityCode: string;
  typeCode: DocumentTypeCode;
  year: number;
  sequence: number;
}

/**
 * Builds a unique ID from its parts. Throws if any part is invalid so malformed
 * IDs can never be persisted.
 */
export function formatUniqueId(parts: UniqueIdParts): string {
  const { entityCode, typeCode, year, sequence } = parts;

  if (!ENTITY_CODE_REGEX.test(entityCode)) {
    throw new Error(`Invalid entity code: "${entityCode}"`);
  }
  if (!isDocumentTypeCode(typeCode)) {
    throw new Error(`Invalid document-type code: "${typeCode}"`);
  }
  if (!Number.isInteger(year) || year < 1000 || year > 9999) {
    throw new Error(`Invalid year: "${year}" (expected 4 digits)`);
  }
  if (!Number.isInteger(sequence) || sequence < SEQUENCE_MIN || sequence > SEQUENCE_MAX) {
    throw new Error(
      `Invalid sequence: "${sequence}" (expected ${SEQUENCE_MIN}..${SEQUENCE_MAX})`,
    );
  }

  const seq = String(sequence).padStart(6, '0');
  return `${entityCode}-${typeCode}-${year}-${seq}`;
}

/** True if `value` is a well-formed unique ID with a registered type code. */
export function isUniqueId(value: string): boolean {
  if (!UNIQUE_ID_REGEX.test(value)) {
    return false;
  }
  return isDocumentTypeCode(value.split('-')[1]);
}

/** Parses a unique ID into its parts, or returns `null` if it is malformed. */
export function parseUniqueId(value: string): UniqueIdParts | null {
  if (!UNIQUE_ID_REGEX.test(value)) {
    return null;
  }
  const [entityCode, typeCode, yearStr, seqStr] = value.split('-');
  if (!isDocumentTypeCode(typeCode)) {
    return null;
  }
  return {
    entityCode,
    typeCode,
    year: Number(yearStr),
    sequence: Number(seqStr),
  };
}
