-- V5: replace the section/tab onboarding model with the FOUR onboarding forms
-- (Form 1 Personal Details, Form 2 Employee Info, Form 3 Previous Employment [repeatable],
-- Form 4 Documents [uploads]), a single captured e-signature, and the generated PDFs
-- (one per form + a merged complete application). Field-level encryption (AES-GCM,
-- FIELD_ENC_KEY) is applied in the application layer to the sensitive columns flagged below
-- and to workingExperiences[].salaryCtc inside form1 "data".

-- ---------------------------------------------------------------------------
-- Drop the old tab model (employee form data is re-captured under the new model).
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS "profile_sections";
DROP TABLE IF EXISTS "documents";
DROP TYPE IF EXISTS "SectionKey";
DROP TYPE IF EXISTS "DocumentType";

-- ---------------------------------------------------------------------------
-- New enum types
-- ---------------------------------------------------------------------------
-- Form 4 document slots. The per-employment slots (offer/hike/relieving) carry a groupIndex 1..4.
CREATE TYPE "DocumentType" AS ENUM (
  'SECONDARY', 'INTERMEDIATE', 'DIPLOMA', 'GRADUATION', 'POST_GRADUATION',
  'OFFER_OR_APPOINTMENT_LETTER', 'HIKE_LETTER', 'RELIEVING_LETTER',
  'AADHAAR', 'PAN', 'VOTER_ID', 'DRIVING_LICENCE', 'PASSPORT', 'OTHER'
);

CREATE TYPE "GeneratedDocumentKind" AS ENUM ('FORM1', 'FORM2', 'FORM3', 'FORM4_MANIFEST', 'MERGED');

-- ---------------------------------------------------------------------------
-- Form 1 — Personal Details (one row per employee). Non-sensitive scalars and the
-- educational / working-experience / family / character-reference child rows live in "data"
-- (JSONB). "offeredCtc" is encrypted at rest; workingExperiences[].salaryCtc is encrypted
-- within "data" by the application.
-- ---------------------------------------------------------------------------
CREATE TABLE "form1_personal" (
    "id" TEXT NOT NULL,
    "employeeId" TEXT NOT NULL,
    "data" JSONB,
    "offeredCtc" TEXT,
    "status" "SectionStatus" NOT NULL DEFAULT 'DRAFT',
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,
    CONSTRAINT "form1_personal_pkey" PRIMARY KEY ("id")
);

-- ---------------------------------------------------------------------------
-- Form 2 — Employee Info (one row per employee). "sparkId" is HR/admin-set. "panNumber"
-- and "axisAccountNumber" are encrypted at rest. The employeeId shown on Form 2 is the
-- minted employee code (null until Manager approval) and is NOT stored here — it is stamped
-- onto the generated PDF once approval mints it.
-- ---------------------------------------------------------------------------
CREATE TABLE "form2_info" (
    "id" TEXT NOT NULL,
    "employeeId" TEXT NOT NULL,
    "data" JSONB,
    "sparkId" TEXT,
    "panNumber" TEXT,
    "axisAccountNumber" TEXT,
    "status" "SectionStatus" NOT NULL DEFAULT 'DRAFT',
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,
    CONSTRAINT "form2_info_pkey" PRIMARY KEY ("id")
);

-- ---------------------------------------------------------------------------
-- Form 3 — Previous Employment (repeatable; many rows per employee). "companyName" is the
-- PREVIOUS employer (employee-entered). "lastDrawnSalary" is encrypted at rest.
-- ---------------------------------------------------------------------------
CREATE TABLE "form3_prev_employment" (
    "id" TEXT NOT NULL,
    "employeeId" TEXT NOT NULL,
    "orderIndex" INTEGER NOT NULL DEFAULT 0,
    "companyName" TEXT,
    "companyAddress" TEXT,
    "dateOfJoining" TEXT,
    "dateOfRelieving" TEXT,
    "designation" TEXT,
    "lastDrawnSalary" TEXT,
    "jobType" TEXT,
    "reasonForLeaving" TEXT,
    "reportingTo" TEXT,
    "roContact" TEXT,
    "hrNameContact" TEXT,
    "status" "SectionStatus" NOT NULL DEFAULT 'DRAFT',
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,
    CONSTRAINT "form3_prev_employment_pkey" PRIMARY KEY ("id")
);

-- ---------------------------------------------------------------------------
-- Form 4 — Documents (uploads). "docType" is the slot; "groupIndex" (1..4) distinguishes the
-- per-employment groups (offer/hike/relieving), otherwise NULL.
-- ---------------------------------------------------------------------------
CREATE TABLE "documents" (
    "id" TEXT NOT NULL,
    "employeeId" TEXT NOT NULL,
    "docType" "DocumentType" NOT NULL,
    "groupIndex" INTEGER,
    "fileName" TEXT NOT NULL,
    "storageKey" TEXT NOT NULL,
    "mimeType" TEXT NOT NULL,
    "sha256" TEXT,
    "status" "DocumentStatus" NOT NULL DEFAULT 'PENDING',
    "uploadedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "documents_pkey" PRIMARY KEY ("id")
);

-- ---------------------------------------------------------------------------
-- The single captured e-signature (drawn or typed), stamped onto Forms 1 & 2 + the merged PDF.
-- ---------------------------------------------------------------------------
CREATE TABLE "signatures" (
    "id" TEXT NOT NULL,
    "employeeId" TEXT NOT NULL,
    "storageKey" TEXT NOT NULL,
    "type" TEXT NOT NULL DEFAULT 'DRAWN',
    "signedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "signatures_pkey" PRIMARY KEY ("id")
);

-- ---------------------------------------------------------------------------
-- Generated PDFs (one per form + a merged complete application). Regenerated on edit and
-- again when Manager approval mints the employee ID.
-- ---------------------------------------------------------------------------
CREATE TABLE "generated_documents" (
    "id" TEXT NOT NULL,
    "employeeId" TEXT NOT NULL,
    "kind" "GeneratedDocumentKind" NOT NULL,
    "fileName" TEXT NOT NULL,
    "storageKey" TEXT NOT NULL,
    "sha256" TEXT,
    "generatedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "generated_documents_pkey" PRIMARY KEY ("id")
);

-- ---------------------------------------------------------------------------
-- Indexes
-- ---------------------------------------------------------------------------
CREATE UNIQUE INDEX "form1_personal_employeeId_key" ON "form1_personal"("employeeId");
CREATE UNIQUE INDEX "form2_info_employeeId_key" ON "form2_info"("employeeId");
CREATE INDEX "form3_prev_employment_employeeId_idx" ON "form3_prev_employment"("employeeId");
CREATE INDEX "documents_employeeId_idx" ON "documents"("employeeId");
CREATE UNIQUE INDEX "signatures_employeeId_key" ON "signatures"("employeeId");
CREATE INDEX "generated_documents_employeeId_idx" ON "generated_documents"("employeeId");
CREATE UNIQUE INDEX "generated_documents_employeeId_kind_key" ON "generated_documents"("employeeId", "kind");

-- ---------------------------------------------------------------------------
-- Foreign keys (mirror the RESTRICT-on-delete rule used across the employee record)
-- ---------------------------------------------------------------------------
ALTER TABLE "form1_personal" ADD CONSTRAINT "form1_personal_employeeId_fkey" FOREIGN KEY ("employeeId") REFERENCES "employees"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "form2_info" ADD CONSTRAINT "form2_info_employeeId_fkey" FOREIGN KEY ("employeeId") REFERENCES "employees"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "form3_prev_employment" ADD CONSTRAINT "form3_prev_employment_employeeId_fkey" FOREIGN KEY ("employeeId") REFERENCES "employees"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "documents" ADD CONSTRAINT "documents_employeeId_fkey" FOREIGN KEY ("employeeId") REFERENCES "employees"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "signatures" ADD CONSTRAINT "signatures_employeeId_fkey" FOREIGN KEY ("employeeId") REFERENCES "employees"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "generated_documents" ADD CONSTRAINT "generated_documents_employeeId_fkey" FOREIGN KEY ("employeeId") REFERENCES "employees"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
