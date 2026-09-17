/**
 * Canonical hrorg.in enums (ARCHITECTURE.md §4). Kept as local constants (the Java backend is the
 * authoritative source; the OpenAPI schema renders these as matching string-literal unions). The
 * web imports them from `@/lib/contract`.
 *
 * Pattern: a `const` object (runtime values) + a string-union `type` of the same name.
 */

export const UserRole = {
  SUPER_ADMIN: 'SUPER_ADMIN',
  ACCOUNTS_ADMIN: 'ACCOUNTS_ADMIN',
  HIERARCHY: 'HIERARCHY',
  COMPANY_ADMIN: 'COMPANY_ADMIN',
  HR: 'HR',
  MANAGER: 'MANAGER',
  ACCOUNTANT: 'ACCOUNTANT',
} as const;
export type UserRole = (typeof UserRole)[keyof typeof UserRole];

export const EmployeeStatus = {
  INVITED: 'INVITED',
  IN_PROGRESS: 'IN_PROGRESS',
  SUBMITTED: 'SUBMITTED',
  REVISION_REQUESTED: 'REVISION_REQUESTED',
  HR_VERIFIED: 'HR_VERIFIED',
  APPROVED: 'APPROVED',
  REJECTED: 'REJECTED',
  OFFBOARDED: 'OFFBOARDED',
} as const;
export type EmployeeStatus = (typeof EmployeeStatus)[keyof typeof EmployeeStatus];

/**
 * How the record was opened (§3.2): a NEW_HIRE gets the offer letter + selection email and onboards themselves; an
 * EXISTING_EMPLOYEE (already works at the company, no IHRMS record yet) gets no offer and no email — HR enters their
 * record via /employees/{id}/onboarding and approves it, keeping the employee ID they already have.
 */
export const OnboardingType = {
  NEW_HIRE: 'NEW_HIRE',
  EXISTING_EMPLOYEE: 'EXISTING_EMPLOYEE',
} as const;
export type OnboardingType = (typeof OnboardingType)[keyof typeof OnboardingType];

/** Status of each form (Form 1/2/3) and review item. */
export const SectionStatus = {
  DRAFT: 'DRAFT',
  SUBMITTED: 'SUBMITTED',
  VERIFIED: 'VERIFIED',
  REVISION_REQUESTED: 'REVISION_REQUESTED',
  REJECTED: 'REJECTED',
} as const;
export type SectionStatus = (typeof SectionStatus)[keyof typeof SectionStatus];

/** Form 4 document slots. The per-employment slots carry a groupIndex (1..4). */
export const DocumentType = {
  SECONDARY: 'SECONDARY',
  INTERMEDIATE: 'INTERMEDIATE',
  DIPLOMA: 'DIPLOMA',
  GRADUATION: 'GRADUATION',
  POST_GRADUATION: 'POST_GRADUATION',
  OFFER_OR_APPOINTMENT_LETTER: 'OFFER_OR_APPOINTMENT_LETTER',
  HIKE_LETTER: 'HIKE_LETTER',
  RELIEVING_LETTER: 'RELIEVING_LETTER',
  AADHAAR: 'AADHAAR',
  PAN: 'PAN',
  VOTER_ID: 'VOTER_ID',
  DRIVING_LICENCE: 'DRIVING_LICENCE',
  PASSPORT: 'PASSPORT',
  ITR: 'ITR',
  OTHER: 'OTHER',
} as const;
export type DocumentType = (typeof DocumentType)[keyof typeof DocumentType];

/** The generated onboarding PDFs (one per form + a merged complete application). */
export const GeneratedDocumentKind = {
  FORM1: 'FORM1',
  FORM2: 'FORM2',
  FORM3: 'FORM3',
  FORM4_MANIFEST: 'FORM4_MANIFEST',
  MERGED: 'MERGED',
} as const;
export type GeneratedDocumentKind =
  (typeof GeneratedDocumentKind)[keyof typeof GeneratedDocumentKind];

export const DocumentStatus = {
  PENDING: 'PENDING',
  UPLOADED: 'UPLOADED',
  VERIFIED: 'VERIFIED',
  REVISION_REQUESTED: 'REVISION_REQUESTED',
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
  LEAVE_REQUESTED: 'LEAVE_REQUESTED',
} as const;
export type NotificationType = (typeof NotificationType)[keyof typeof NotificationType];

// Leave requests (§8b).
export const LeaveType = {
  CASUAL: 'CASUAL',
  SICK: 'SICK',
  UNPAID: 'UNPAID',
} as const;
export type LeaveType = (typeof LeaveType)[keyof typeof LeaveType];

export const LeaveStatus = {
  PENDING: 'PENDING',
  APPROVED: 'APPROVED',
  REJECTED: 'REJECTED',
  CANCELLED: 'CANCELLED',
} as const;
export type LeaveStatus = (typeof LeaveStatus)[keyof typeof LeaveStatus];

// HR/Accounts document requests — Accounts side (§8d).
export const RequestType = {
  PAYSLIP: 'PAYSLIP',
  SALARY_CERTIFICATE: 'SALARY_CERTIFICATE',
  FORM16: 'FORM16',
  TAX_DOCUMENT: 'TAX_DOCUMENT',
  OTHER: 'OTHER',
  // §3.6 stage 3 — HR-routed offboarding letters (requested only via the workspace Offboarding section).
  RELIEVING_LETTER: 'RELIEVING_LETTER',
  EXPERIENCE_LETTER: 'EXPERIENCE_LETTER',
} as const;
export type RequestType = (typeof RequestType)[keyof typeof RequestType];

/** The accountant-routed types the generic "HR/Accounts Requests" picker offers (letters excluded). */
export const GENERIC_REQUEST_TYPES: RequestType[] = [
  RequestType.PAYSLIP,
  RequestType.SALARY_CERTIFICATE,
  RequestType.FORM16,
  RequestType.TAX_DOCUMENT,
  RequestType.OTHER,
];

export const RequestStatus = {
  SUBMITTED: 'SUBMITTED',
  IN_PROGRESS: 'IN_PROGRESS',
  RESOLVED: 'RESOLVED',
  CANCELLED: 'CANCELLED',
} as const;
export type RequestStatus = (typeof RequestStatus)[keyof typeof RequestStatus];

/**
 * Registry of every shared enum, keyed by name. The API's enum-parity test iterates
 * this and compares against the Prisma-generated enums.
 */
export const SHARED_ENUMS = {
  UserRole,
  EmployeeStatus,
  OnboardingType,
  SectionStatus,
  DocumentType,
  DocumentStatus,
  GeneratedDocumentKind,
  ApprovalStatus,
  NotificationType,
  LeaveType,
  LeaveStatus,
  RequestType,
  RequestStatus,
} as const;

export type SharedEnumName = keyof typeof SHARED_ENUMS;

/** Runtime values of an enum const-object as a string array. */
export function enumValues<T extends Record<string, string>>(e: T): string[] {
  return Object.values(e);
}
