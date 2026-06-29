/**
 * Canonical IHRMS enums (ARCHITECTURE.md §4) — the SINGLE source of truth.
 *
 * Prisma re-declares matching DB enums in `apps/api/prisma/schema.prisma`; an
 * enum-parity unit test asserts the two never drift. Both apps import these from
 * `@ihrms/shared` — nothing duplicates them.
 *
 * Pattern: a `const` object (runtime values) + a string-union `type` of the same name.
 */

export const UserRole = {
  SUPER_ADMIN: 'SUPER_ADMIN',
  COMPANY_ADMIN: 'COMPANY_ADMIN',
  HR: 'HR',
  MANAGER: 'MANAGER',
} as const;
export type UserRole = (typeof UserRole)[keyof typeof UserRole];

export const EmployeeStatus = {
  INVITED: 'INVITED',
  IN_PROGRESS: 'IN_PROGRESS',
  SUBMITTED: 'SUBMITTED',
  HR_VERIFIED: 'HR_VERIFIED',
  APPROVED: 'APPROVED',
  REJECTED: 'REJECTED',
} as const;
export type EmployeeStatus = (typeof EmployeeStatus)[keyof typeof EmployeeStatus];

export const SectionKey = {
  PERSONAL: 'PERSONAL',
  BACKGROUND: 'BACKGROUND',
  GOVERNMENT: 'GOVERNMENT',
} as const;
export type SectionKey = (typeof SectionKey)[keyof typeof SectionKey];

export const SectionStatus = {
  DRAFT: 'DRAFT',
  SUBMITTED: 'SUBMITTED',
  VERIFIED: 'VERIFIED',
  REJECTED: 'REJECTED',
} as const;
export type SectionStatus = (typeof SectionStatus)[keyof typeof SectionStatus];

export const DocumentType = {
  EXPERIENCE_LETTER: 'EXPERIENCE_LETTER',
  PAN: 'PAN',
  AADHAAR: 'AADHAAR',
  BGV_DOCUMENT: 'BGV_DOCUMENT',
  OTHER: 'OTHER',
} as const;
export type DocumentType = (typeof DocumentType)[keyof typeof DocumentType];

export const DocumentStatus = {
  UPLOADED: 'UPLOADED',
  VERIFIED: 'VERIFIED',
  REJECTED: 'REJECTED',
} as const;
export type DocumentStatus = (typeof DocumentStatus)[keyof typeof DocumentStatus];

export const ApprovalStatus = {
  PENDING: 'PENDING',
  APPROVED: 'APPROVED',
  REJECTED: 'REJECTED',
} as const;
export type ApprovalStatus = (typeof ApprovalStatus)[keyof typeof ApprovalStatus];

export const NotificationType = {
  EMPLOYEE_ONBOARDED: 'EMPLOYEE_ONBOARDED',
  EMPLOYEE_SUBMITTED: 'EMPLOYEE_SUBMITTED',
  APPROVAL_REQUESTED: 'APPROVAL_REQUESTED',
  EMPLOYEE_APPROVED: 'EMPLOYEE_APPROVED',
  EMPLOYEE_REJECTED: 'EMPLOYEE_REJECTED',
} as const;
export type NotificationType = (typeof NotificationType)[keyof typeof NotificationType];

/**
 * Registry of every shared enum, keyed by name. The API's enum-parity test iterates
 * this and compares against the Prisma-generated enums.
 */
export const SHARED_ENUMS = {
  UserRole,
  EmployeeStatus,
  SectionKey,
  SectionStatus,
  DocumentType,
  DocumentStatus,
  ApprovalStatus,
  NotificationType,
} as const;

export type SharedEnumName = keyof typeof SHARED_ENUMS;

/** Runtime values of an enum const-object as a string array. */
export function enumValues<T extends Record<string, string>>(e: T): string[] {
  return Object.values(e);
}
