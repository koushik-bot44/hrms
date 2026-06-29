/**
 * Unique employee-ID helpers (ARCHITECTURE.md §5).
 *
 * Format: `{COMPANY_CODE}-EMP-{NNNNNN}`  e.g. `ACME-EMP-000123`
 *   COMPANY_CODE  the company's short mnemonic — uppercase, starts with a letter, 2..16 chars
 *   NNNNNN        6-digit zero-padded sequence, UNIQUE WITHIN THE COMPANY
 *
 * (§5 marks the format as a proposal; it lives here so it can be adjusted in one place.)
 */

export const COMPANY_CODE_REGEX = /^[A-Z][A-Z0-9]{1,15}$/;
export const EMPLOYEE_CODE_REGEX = /^[A-Z][A-Z0-9]{1,15}-EMP-\d{6}$/;

export const EMPLOYEE_SEQ_MIN = 1;
export const EMPLOYEE_SEQ_MAX = 999_999;

export interface EmployeeCodeParts {
  companyCode: string;
  sequence: number;
}

/** Build an employee code from its parts. Throws on invalid input so malformed codes can't persist. */
export function formatEmployeeCode(parts: EmployeeCodeParts): string {
  const { companyCode, sequence } = parts;

  if (!COMPANY_CODE_REGEX.test(companyCode)) {
    throw new Error(`Invalid company code: "${companyCode}"`);
  }
  if (!Number.isInteger(sequence) || sequence < EMPLOYEE_SEQ_MIN || sequence > EMPLOYEE_SEQ_MAX) {
    throw new Error(
      `Invalid sequence: "${sequence}" (expected ${EMPLOYEE_SEQ_MIN}..${EMPLOYEE_SEQ_MAX})`,
    );
  }

  return `${companyCode}-EMP-${String(sequence).padStart(6, '0')}`;
}

/** True if `value` is a well-formed employee code. */
export function isEmployeeCode(value: string): boolean {
  return EMPLOYEE_CODE_REGEX.test(value);
}

/** Parse an employee code into its parts, or `null` if malformed. */
export function parseEmployeeCode(value: string): EmployeeCodeParts | null {
  if (!EMPLOYEE_CODE_REGEX.test(value)) {
    return null;
  }
  const [companyCode, , seq] = value.split('-');
  return { companyCode, sequence: Number(seq) };
}
