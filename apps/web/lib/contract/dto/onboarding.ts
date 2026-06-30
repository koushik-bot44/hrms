import { z } from 'zod';
import { DocumentStatus, DocumentType, SectionKey } from '../enums';

/**
 * Employee onboarding contracts (ARCHITECTURE.md §3.2 / §4). The employee fills tabbed
 * section forms and uploads documents under their own record, then submits. Section data
 * shapes, upload constraints, and the submission requirements all live here so the api
 * (validation + gate) and web (forms + progress) share one source of truth.
 */

// ---------------------------------------------------------------------------
// Section field data (validated per SectionKey)
// ---------------------------------------------------------------------------

export const PersonalSectionSchema = z.object({
  fullName: z.string().trim().min(2, 'Full name is required').max(120),
  dateOfBirth: z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'Use YYYY-MM-DD'),
  phone: z.string().trim().min(7, 'Enter a valid phone number').max(20),
  addressLine: z.string().trim().min(3, 'Address is required').max(200),
  city: z.string().trim().min(2, 'City is required').max(80),
});
export type PersonalSection = z.infer<typeof PersonalSectionSchema>;

export const BackgroundSectionSchema = z.object({
  previousCompany: z.string().trim().max(120).optional().or(z.literal('')),
  yearsOfExperience: z.coerce.number().min(0).max(60).optional(),
  notes: z.string().trim().max(1000).optional().or(z.literal('')),
});
export type BackgroundSection = z.infer<typeof BackgroundSectionSchema>;

export const GovernmentSectionSchema = z.object({
  panNumber: z
    .string()
    .trim()
    .toUpperCase()
    .regex(/^[A-Z]{5}[0-9]{4}[A-Z]$/, 'Enter a valid PAN (e.g. ABCDE1234F)'),
  aadhaarLast4: z
    .string()
    .trim()
    .regex(/^\d{4}$/, 'Last 4 digits only')
    .optional()
    .or(z.literal('')),
});
export type GovernmentSection = z.infer<typeof GovernmentSectionSchema>;

/** SectionKey -> the zod schema validating that section's `data` Json. */
export const SECTION_DATA_SCHEMAS = {
  [SectionKey.PERSONAL]: PersonalSectionSchema,
  [SectionKey.BACKGROUND]: BackgroundSectionSchema,
  [SectionKey.GOVERNMENT]: GovernmentSectionSchema,
} satisfies Record<SectionKey, z.ZodTypeAny>;

// ---------------------------------------------------------------------------
// Documents & upload constraints
// ---------------------------------------------------------------------------

export const MAX_UPLOAD_BYTES = 10 * 1024 * 1024; // 10 MB
/** Max files per document type, per section (e.g. PAN front + back). Mirrors the API cap. */
export const MAX_DOCUMENTS_PER_TYPE = 2;
export const ALLOWED_UPLOAD_MIME_TYPES = [
  'application/pdf',
  'image/png',
  'image/jpeg',
] as const;
export type AllowedUploadMimeType = (typeof ALLOWED_UPLOAD_MIME_TYPES)[number];

/** Which document types belong to each section (drives the upload UI). */
export const SECTION_DOCUMENT_TYPES = {
  [SectionKey.PERSONAL]: [] as DocumentType[],
  [SectionKey.BACKGROUND]: [DocumentType.EXPERIENCE_LETTER, DocumentType.OTHER],
  [SectionKey.GOVERNMENT]: [DocumentType.PAN, DocumentType.AADHAAR],
} satisfies Record<SectionKey, DocumentType[]>;

// ---------------------------------------------------------------------------
// Submission requirements (shared by the API gate and the web progress bar)
// ---------------------------------------------------------------------------

export const REQUIRED_SECTIONS: SectionKey[] = [SectionKey.PERSONAL, SectionKey.GOVERNMENT];
export const REQUIRED_DOCUMENTS: Array<{ sectionKey: SectionKey; docType: DocumentType }> = [
  { sectionKey: SectionKey.GOVERNMENT, docType: DocumentType.PAN },
];

const DONE_DOC_STATUSES: DocumentStatus[] = [DocumentStatus.UPLOADED, DocumentStatus.VERIFIED];

export interface SubmissionEvaluation {
  complete: boolean;
  missingSections: SectionKey[];
  missingDocuments: Array<{ sectionKey: SectionKey; docType: DocumentType }>;
  requiredTotal: number;
  completedTotal: number;
}

/** Decide whether an employee's record is complete enough to submit (shared logic). */
export function evaluateSubmission(
  sections: Array<{ key: SectionKey }>,
  documents: Array<{ sectionKey: SectionKey; docType: DocumentType; status: DocumentStatus }>,
): SubmissionEvaluation {
  const savedKeys = new Set(sections.map((s) => s.key));
  const missingSections = REQUIRED_SECTIONS.filter((k) => !savedKeys.has(k));
  const missingDocuments = REQUIRED_DOCUMENTS.filter(
    (req) =>
      !documents.some(
        (d) =>
          d.sectionKey === req.sectionKey &&
          d.docType === req.docType &&
          DONE_DOC_STATUSES.includes(d.status),
      ),
  );
  const requiredTotal = REQUIRED_SECTIONS.length + REQUIRED_DOCUMENTS.length;
  const completedTotal = requiredTotal - missingSections.length - missingDocuments.length;
  return {
    complete: missingSections.length === 0 && missingDocuments.length === 0,
    missingSections,
    missingDocuments,
    requiredTotal,
    completedTotal,
  };
}

// ---------------------------------------------------------------------------
// Requests
// ---------------------------------------------------------------------------

export const SaveSectionSchema = z.object({
  data: z.record(z.unknown()),
});
export type SaveSectionInput = z.infer<typeof SaveSectionSchema>;

export const DocumentUploadRequestSchema = z.object({
  sectionKey: z.nativeEnum(SectionKey),
  docType: z.nativeEnum(DocumentType),
  fileName: z.string().trim().min(1, 'File name is required').max(255),
  mimeType: z.enum(ALLOWED_UPLOAD_MIME_TYPES),
  sizeBytes: z.number().int().positive().max(MAX_UPLOAD_BYTES, 'File is too large'),
});
export type DocumentUploadRequestInput = z.infer<typeof DocumentUploadRequestSchema>;

// Response shapes (ProfileSectionDto, DocumentDto, OnboardingDashboard, PresignedUpload,
// PresignedView — storageKey is NEVER exposed) are derived from the Java OpenAPI schema in
// `../responses.ts`.
