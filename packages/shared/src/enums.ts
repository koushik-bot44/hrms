/**
 * Canonical enums for the CDPP platform.
 *
 * These are the SINGLE source of truth. Prisma re-declares matching DB enums in
 * `apps/api/prisma/schema.prisma`; an enum-parity unit test in the API asserts the
 * two never drift. Both apps import these from `@cdpp/shared` — nothing duplicates them.
 *
 * Pattern: a `const` object (runtime values) + a string-union `type` of the same name.
 */

export const Role = {
  SUPER_ADMIN: 'SUPER_ADMIN',
  HR_OPERATOR: 'HR_OPERATOR',
  EMPLOYEE: 'EMPLOYEE',
} as const;
export type Role = (typeof Role)[keyof typeof Role];

export const EmploymentType = {
  FULL_TIME: 'FULL_TIME',
  PART_TIME: 'PART_TIME',
  CONTRACT: 'CONTRACT',
  INTERN: 'INTERN',
} as const;
export type EmploymentType = (typeof EmploymentType)[keyof typeof EmploymentType];

export const WorkAuthStatus = {
  CITIZEN: 'CITIZEN',
  PERMANENT_RESIDENT: 'PERMANENT_RESIDENT',
  WORK_VISA: 'WORK_VISA',
  PENDING: 'PENDING',
  NOT_AUTHORIZED: 'NOT_AUTHORIZED',
} as const;
export type WorkAuthStatus = (typeof WorkAuthStatus)[keyof typeof WorkAuthStatus];

export const OnboardingState = {
  INVITED: 'INVITED',
  IN_PROGRESS: 'IN_PROGRESS',
  PENDING_REVIEW: 'PENDING_REVIEW',
  COMPLETED: 'COMPLETED',
  REJECTED: 'REJECTED',
} as const;
export type OnboardingState = (typeof OnboardingState)[keyof typeof OnboardingState];

export const DocumentClass = {
  LETTER: 'LETTER',
  AGREEMENT: 'AGREEMENT',
  CERTIFICATE: 'CERTIFICATE',
  VERIFICATION: 'VERIFICATION',
} as const;
export type DocumentClass = (typeof DocumentClass)[keyof typeof DocumentClass];

export const DocumentStatus = {
  DRAFT: 'DRAFT',
  PENDING: 'PENDING',
  ISSUED: 'ISSUED',
  SIGNED: 'SIGNED',
  REVOKED: 'REVOKED',
} as const;
export type DocumentStatus = (typeof DocumentStatus)[keyof typeof DocumentStatus];

export const RequirementStatus = {
  PENDING: 'PENDING',
  SUBMITTED: 'SUBMITTED',
  APPROVED: 'APPROVED',
  REJECTED: 'REJECTED',
  WAIVED: 'WAIVED',
} as const;
export type RequirementStatus = (typeof RequirementStatus)[keyof typeof RequirementStatus];

export const ConsentKind = {
  BACKGROUND_CHECK: 'BACKGROUND_CHECK',
  DATA_PROCESSING: 'DATA_PROCESSING',
  ELECTRONIC_SIGNATURE: 'ELECTRONIC_SIGNATURE',
} as const;
export type ConsentKind = (typeof ConsentKind)[keyof typeof ConsentKind];

/**
 * Registry of every shared enum, keyed by name. Used by the API's enum-parity test
 * to iterate and compare against the Prisma-generated enums.
 */
export const SHARED_ENUMS = {
  Role,
  EmploymentType,
  WorkAuthStatus,
  OnboardingState,
  DocumentClass,
  DocumentStatus,
  RequirementStatus,
  ConsentKind,
} as const;

export type SharedEnumName = keyof typeof SHARED_ENUMS;

/** Returns the runtime values of an enum const-object as a string array. */
export function enumValues<T extends Record<string, string>>(e: T): string[] {
  return Object.values(e);
}
