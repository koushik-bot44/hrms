-- V8: per-item "send back for revision" (§3.3). HR can return a single form/document to the
-- employee for changes without rejecting the whole application. Additive only:
--   * a new REVISION_REQUESTED value on the SectionStatus / DocumentStatus / EmployeeStatus enums, and
--   * a nullable revisionNote + revisionRequestedAt on each reviewable item (the four forms + documents).
-- PostgreSQL 12+ permits ALTER TYPE ... ADD VALUE inside a transaction as long as the new value is not
-- USED in the same transaction (it is not here — only added, and columns added — so Flyway's wrapping
-- transaction is fine). IF NOT EXISTS keeps the migration idempotent.

ALTER TYPE "SectionStatus" ADD VALUE IF NOT EXISTS 'REVISION_REQUESTED';
ALTER TYPE "DocumentStatus" ADD VALUE IF NOT EXISTS 'REVISION_REQUESTED';
ALTER TYPE "EmployeeStatus" ADD VALUE IF NOT EXISTS 'REVISION_REQUESTED';

ALTER TABLE "form1_personal"
    ADD COLUMN "revisionNote" TEXT,
    ADD COLUMN "revisionRequestedAt" TIMESTAMP(3);

ALTER TABLE "form2_info"
    ADD COLUMN "revisionNote" TEXT,
    ADD COLUMN "revisionRequestedAt" TIMESTAMP(3);

ALTER TABLE "form3_prev_employment"
    ADD COLUMN "revisionNote" TEXT,
    ADD COLUMN "revisionRequestedAt" TIMESTAMP(3);

ALTER TABLE "documents"
    ADD COLUMN "revisionNote" TEXT,
    ADD COLUMN "revisionRequestedAt" TIMESTAMP(3);
