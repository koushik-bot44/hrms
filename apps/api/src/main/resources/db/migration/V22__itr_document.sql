-- ITR Form (§3.2 Form 4, Identity Proofs group) — additive only.
--
-- 1) New Form-4 document slot. PG enums are extended, never re-created; the value is appended so all
--    existing DocumentType rows keep their labels/order. (IF NOT EXISTS makes the migration re-runnable.)
ALTER TYPE "DocumentType" ADD VALUE IF NOT EXISTS 'ITR';

-- 2) Per-employee "ITR is mandatory" flag. ITR is required for NEW onboardings only; every EXISTING row
--    (any status, including an in-flight revision loop) defaults to false so no one already onboarded is
--    retroactively marked incomplete or re-opened. EmployeesService sets this true at onboard going forward.
ALTER TABLE "employees" ADD COLUMN "itrRequired" BOOLEAN NOT NULL DEFAULT false;
