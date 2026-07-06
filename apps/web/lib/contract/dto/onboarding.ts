import { z } from 'zod';
import { DocumentStatus, DocumentType } from '../enums';
import type { DocumentDto, Form1View, Form2View, SignatureView } from '../responses';

/**
 * Employee onboarding contracts — the four-form stepper (ARCHITECTURE.md §3.2). Form 1 Personal
 * Details, Form 2 Employee Info, Form 3 Previous Employment (repeatable), Form 4 Documents (uploads),
 * then a captured e-signature and submit → the system generates the PDFs. Save is lenient (drafts
 * round-trip); required-field completeness is enforced at submit (mirrored here for the UI). Response
 * shapes are derived from the Java OpenAPI schema in `../responses.ts`.
 */

// ---------------------------------------------------------------------------
// Upload constraints + the Form 4 document slots
// ---------------------------------------------------------------------------

export const MAX_UPLOAD_BYTES = 10 * 1024 * 1024; // 10 MB
export const MAX_DOCUMENTS_PER_SLOT = 2;
export const ALLOWED_UPLOAD_MIME_TYPES = [
  'application/pdf',
  'image/png',
  'image/jpeg',
] as const;
export type AllowedUploadMimeType = (typeof ALLOWED_UPLOAD_MIME_TYPES)[number];

export const EDUCATION_SLOTS: DocumentType[] = [
  DocumentType.SECONDARY,
  DocumentType.INTERMEDIATE,
  DocumentType.DIPLOMA,
  DocumentType.GRADUATION,
  DocumentType.POST_GRADUATION,
];
/** Per-employment slots — each is uploaded once per employment group (groupIndex 1..4). */
export const EMPLOYMENT_SLOTS: DocumentType[] = [
  DocumentType.OFFER_OR_APPOINTMENT_LETTER,
  DocumentType.HIKE_LETTER,
  DocumentType.RELIEVING_LETTER,
];
export const IDENTITY_SLOTS: DocumentType[] = [
  DocumentType.AADHAAR,
  DocumentType.PAN,
  DocumentType.VOTER_ID,
  DocumentType.DRIVING_LICENCE,
  DocumentType.PASSPORT,
];
export const EMPLOYMENT_GROUPS = [1, 2, 3, 4] as const;
export const REQUIRED_DOC_TYPES: DocumentType[] = [DocumentType.AADHAAR, DocumentType.PAN];

export const DOCUMENT_TYPE_LABELS: Record<DocumentType, string> = {
  [DocumentType.SECONDARY]: 'Secondary (10th)',
  [DocumentType.INTERMEDIATE]: 'Intermediate (12th)',
  [DocumentType.DIPLOMA]: 'Diploma',
  [DocumentType.GRADUATION]: 'Graduation',
  [DocumentType.POST_GRADUATION]: 'Post-Graduation',
  [DocumentType.OFFER_OR_APPOINTMENT_LETTER]: 'Offer / Appointment Letter',
  [DocumentType.HIKE_LETTER]: 'Hike Letter',
  [DocumentType.RELIEVING_LETTER]: 'Relieving Letter',
  [DocumentType.AADHAAR]: 'Aadhaar',
  [DocumentType.PAN]: 'PAN',
  [DocumentType.VOTER_ID]: 'Voter ID',
  [DocumentType.DRIVING_LICENCE]: 'Driving Licence',
  [DocumentType.PASSPORT]: 'Passport',
  [DocumentType.OTHER]: 'Other',
};

// ---------------------------------------------------------------------------
// Form 1 — Personal Details
// ---------------------------------------------------------------------------

const optional = (max: number) => z.string().trim().max(max).optional().or(z.literal(''));
const dateish = z
  .string()
  .trim()
  .regex(/^(\d{4}-\d{2}-\d{2})?$/, 'Use YYYY-MM-DD')
  .optional()
  .or(z.literal(''));

export const EducationalQualificationSchema = z.object({
  qualification: optional(200),
  university: optional(200),
  yearOfPassing: optional(20),
  percentage: optional(20),
});
export const WorkingExperienceSchema = z.object({
  organization: optional(200),
  period: optional(100),
  designation: optional(150),
  salaryCtc: optional(60),
  reasonForLeaving: optional(200),
});
export const FamilyDetailSchema = z.object({
  name: optional(150),
  age: optional(10),
  relation: optional(60),
  occupation: optional(120),
});
export const CharacterReferenceSchema = z.object({
  name: optional(150),
  address: optional(250),
  phone: optional(30),
});

/**
 * The fixed employee declaration. It is not free-text: the employee affirms it via a checkbox,
 * which stores this exact string in Form 1's `declaration` (rendered verbatim into the PDF).
 */
export const DECLARATION_TEXT =
  'I DECLARE THAT THE INFORMATION GIVEN, HEREIN ABOVE, IS TRUE & CORRECT TO THE BEST OF MY ' +
  'KNOWLEDGE & BELIEF & NOTHING MATERIAL HAS BEEN CONCEALED. I UNDERSTAND THAT IF THE ABOVE ' +
  'INFORMATION IS FOUND FALSE OR INCORRECT, AT ANY TIME DURING MY EMPLOYMENT, MY SERVICE WILL BE ' +
  'TERMINATED FORTHWITH WITHOUT ANY NOTICE OR COMPENSATION.';

export const Form1Schema = z.object({
  name: z.string().trim().min(2, 'Name is required').max(150),
  dateOfBirth: dateish,
  email: optional(180),
  mobile: optional(30),
  designation: optional(150),
  offeredCtc: optional(60),
  currentAddress: optional(300),
  permanentAddress: optional(300),
  maritalStatus: optional(40),
  bloodGroup: optional(10),
  closestRelativeName: optional(150),
  closestRelativePhone: optional(30),
  city: optional(80),
  relationship: optional(60),
  declaration: optional(4000),
  educationalQualifications: z.array(EducationalQualificationSchema).max(20),
  workingExperiences: z.array(WorkingExperienceSchema).max(20),
  familyDetails: z.array(FamilyDetailSchema).max(20),
  // Min-2 is enforced at submit (evaluateOnboarding), not per-save, so drafts stay lenient.
  characterReferences: z.array(CharacterReferenceSchema).max(20),
});
export type Form1Input = z.input<typeof Form1Schema>;
export type Form1Values = z.output<typeof Form1Schema>;

// ---------------------------------------------------------------------------
// Form 2 — Employee Info (employeeId is system-assigned/read-only; sparkId is HR-set)
// ---------------------------------------------------------------------------

export const Form2Schema = z.object({
  fullName: z.string().trim().min(2, 'Full name is required').max(150),
  fatherName: optional(150),
  dateOfBirth: dateish,
  dateOfJoining: dateish,
  bloodGroup: optional(10),
  mobile: optional(30),
  alternateNumber: optional(30),
  officialEmail: optional(180),
  personalEmail: optional(180),
  designation: optional(150),
  documentSubmitted: optional(60),
  vehicleNo2W4W: optional(40),
  panNumber: optional(20),
  axisAccountNumber: optional(40),
  currentAddress: optional(300),
  permanentAddress: optional(300),
});
export type Form2Values = z.output<typeof Form2Schema>;

// ---------------------------------------------------------------------------
// Form 3 — Previous Employment (repeatable)
// ---------------------------------------------------------------------------

export const Form3EntrySchema = z.object({
  companyName: optional(200),
  companyAddress: optional(300),
  dateOfJoining: dateish,
  dateOfRelieving: dateish,
  designation: optional(150),
  lastDrawnSalary: optional(60),
  jobType: optional(60),
  reasonForLeaving: optional(250),
  reportingTo: optional(150),
  roContact: optional(60),
  hrNameContact: optional(150),
});
export const Form3Schema = z.object({
  entries: z.array(Form3EntrySchema).max(20),
});
export type Form3Values = z.output<typeof Form3Schema>;

// ---------------------------------------------------------------------------
// Signature
// ---------------------------------------------------------------------------

export const SignatureSchema = z.object({
  imageDataUrl: z
    .string()
    .regex(/^data:image\/(png|jpeg);base64,.+/, 'Provide a PNG/JPEG signature'),
  type: z.enum(['DRAWN', 'TYPED']),
});
export type SignatureValues = z.output<typeof SignatureSchema>;

// ---------------------------------------------------------------------------
// Submission gate (mirrors the server-side OnboardingCompleteness)
// ---------------------------------------------------------------------------

const DONE_DOC_STATUSES: DocumentStatus[] = [DocumentStatus.UPLOADED, DocumentStatus.VERIFIED];

export function evaluateOnboarding(
  form1: Form1View | null,
  form2: Form2View | null,
  documents: DocumentDto[],
  signature: SignatureView | null,
): { complete: boolean; missing: string[] } {
  const missing: string[] = [];
  if (!form1 || !form1.name) {
    missing.push('Complete Form 1 — Personal Details');
  } else {
    const refs = (form1.characterReferences ?? []).filter((r) => r.name && r.name.trim().length > 0);
    if (refs.length < 2) missing.push('Form 1 — add at least two character references');
    if (!form1.declaration || form1.declaration.trim().length === 0) {
      missing.push('Form 1 — confirm the declaration');
    }
  }
  if (!form2 || !form2.fullName) missing.push('Complete Form 2 — Employee Info');
  for (const req of REQUIRED_DOC_TYPES) {
    const has = documents.some((d) => d.docType === req && DONE_DOC_STATUSES.includes(d.status));
    if (!has) missing.push(`Form 4 — upload your ${DOCUMENT_TYPE_LABELS[req]}`);
  }
  if (!signature) missing.push('Sign to submit');
  return { complete: missing.length === 0, missing };
}
