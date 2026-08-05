-- V34: offboarding stage 3 — LETTERS BECOME GENERATED DOCUMENTS (ARCHITECTURE.md §3.6 stage 3). The Relieving
-- and Experience letters are now company-issued: HR generates the PDF from single-source templates (the
-- employee never fills or signs them). This is a SIBLING table to offboarding_documents rather than an
-- extension of it: a letter is ISSUED (not fill/verify), and the "all documents VERIFIED" gates
-- (OffboardingService.complete + the letter gate) scan every offboarding_documents row expecting VERIFIED —
-- a letter row would jam those gates. Keeping letters separate leaves stage-1/2 untouched. Additive only.
--
-- The request side is UNCHANGED: an employee may still request a letter (a DocumentRequest routed to the case
-- HR, reusing the requests machinery). Issuing a letter for which an open request exists RESOLVES that request
-- (binds the generated PDF); issuing with no request issues directly. The type column reuses "RequestType"
-- (only the two letter values are ever stored). Re-issue overwrites the same row + stable storage key.

CREATE TABLE "offboarding_letters" (
    "id" TEXT NOT NULL,
    "caseId" TEXT NOT NULL,
    "type" "RequestType" NOT NULL,
    -- The token values HR typed at issue (dates, designation, tenure, and — Experience — the gender), JSONB.
    "hrValues" JSONB,
    -- Stable per-(case,type) S3 key of the rendered PDF. Re-issue overwrites the same key.
    "storageKey" TEXT NOT NULL,
    "issuedByUserId" TEXT NOT NULL,
    "issuedAt" timestamp(3) NOT NULL DEFAULT now(),
    CONSTRAINT "offboarding_letters_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX "offboarding_letters_caseId_type_key" ON "offboarding_letters"("caseId", "type");
CREATE INDEX "offboarding_letters_caseId_idx" ON "offboarding_letters"("caseId");

ALTER TABLE "offboarding_letters" ADD CONSTRAINT "offboarding_letters_caseId_fkey" FOREIGN KEY ("caseId") REFERENCES "offboarding_cases"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "offboarding_letters" ADD CONSTRAINT "offboarding_letters_issuedByUserId_fkey" FOREIGN KEY ("issuedByUserId") REFERENCES "users"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
