-- V32: offboarding stage 2 — THE DOCUMENTS (ARCHITECTURE.md §3.6 stage 2). For a case the HIERARCHY has
-- APPROVED, HR sends employee-facing offboarding documents (Exit Formalities, Settlement, Separation) with
-- per-case values; the employee fills/signs them in the workspace; submissions go through an HR verification
-- loop (verify / send-back per document, mirroring Form 4). The Clearance form is an HR-side digital
-- checklist. Additive only. Employee.status is still NOT touched (OFFBOARDED arrives in stage 3).

CREATE TYPE "OffboardingDocType" AS ENUM ('EXIT_FORMALITIES', 'SETTLEMENT', 'SEPARATION');
CREATE TYPE "OffboardingDocStatus" AS ENUM ('PENDING', 'SUBMITTED', 'VERIFIED', 'REVISION_REQUESTED');
CREATE TYPE "ClearanceFinalStatus" AS ENUM ('APPROVED', 'PENDING', 'ON_HOLD');

-- Durable trail for the SENDING HR when the employee submits a document (used only at runtime, never in this
-- migration -> transaction-safe, see V17/V30/V31). The employee has no bell feed (mail + push instead).
ALTER TYPE "NotificationType" ADD VALUE IF NOT EXISTS 'OFFBOARDING_DOC_SUBMITTED';

-- One row per (case, document type). Created PENDING when HR sends; the employee's submission flips it
-- SUBMITTED with a stored PDF; HR verifies (VERIFIED) or sends back (REVISION_REQUESTED + a note). hrValues
-- holds the per-case tokens HR typed at send (JSONB). Per-type idempotency mirrors the agreements pack.
CREATE TABLE "offboarding_documents" (
    "id" TEXT NOT NULL,
    "caseId" TEXT NOT NULL,
    "type" "OffboardingDocType" NOT NULL,
    "status" "OffboardingDocStatus" NOT NULL DEFAULT 'PENDING',
    "sentAt" timestamp(3) NOT NULL DEFAULT now(),
    "sentByUserId" TEXT NOT NULL,
    "hrValues" JSONB,
    "submittedAt" timestamp(3),
    "verifiedAt" timestamp(3),
    "revisionNote" TEXT,
    -- Stable per-type S3 key; null until the employee submits. Resubmission overwrites the same key.
    "storageKey" TEXT,
    CONSTRAINT "offboarding_documents_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX "offboarding_documents_caseId_type_key" ON "offboarding_documents"("caseId", "type");
CREATE INDEX "offboarding_documents_caseId_idx" ON "offboarding_documents"("caseId");

ALTER TABLE "offboarding_documents" ADD CONSTRAINT "offboarding_documents_caseId_fkey" FOREIGN KEY ("caseId") REFERENCES "offboarding_cases"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "offboarding_documents" ADD CONSTRAINT "offboarding_documents_sentByUserId_fkey" FOREIGN KEY ("sentByUserId") REFERENCES "users"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- The HR-side clearance checklist — one per case. `items` is the checklist state (per-item yes/no + remarks,
-- JSONB); the employee-details header is prefilled from the record at render time (not stored). Never shown
-- to the employee.
CREATE TABLE "offboarding_clearance" (
    "id" TEXT NOT NULL,
    "caseId" TEXT NOT NULL,
    "items" JSONB,
    "finalStatus" "ClearanceFinalStatus" NOT NULL DEFAULT 'PENDING',
    "filledByUserId" TEXT NOT NULL,
    "updatedAt" timestamp(3) NOT NULL DEFAULT now(),
    "storageKey" TEXT,
    CONSTRAINT "offboarding_clearance_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX "offboarding_clearance_caseId_key" ON "offboarding_clearance"("caseId");

ALTER TABLE "offboarding_clearance" ADD CONSTRAINT "offboarding_clearance_caseId_fkey" FOREIGN KEY ("caseId") REFERENCES "offboarding_cases"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "offboarding_clearance" ADD CONSTRAINT "offboarding_clearance_filledByUserId_fkey" FOREIGN KEY ("filledByUserId") REFERENCES "users"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
