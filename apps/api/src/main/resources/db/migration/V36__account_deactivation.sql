-- V36: ACCOUNT DEACTIVATION decoupled from offboarding COMPLETION (ARCHITECTURE.md §3.6). Completing an
-- offboarding sets Employee.status = OFFBOARDED (reconciliation only) but NO LONGER blocks login. The AUTH
-- consequence moves to an explicit HR "Deactivate account" action on a new column. Additive only.
--
-- accountDeactivated is the login gate on BOTH employee doors (OTP + credentialed workspace login) + refresh;
-- one-way in v1. deactivatedAt/deactivatedByUserId record who/when (null for the migration-set rows below).

ALTER TABLE "employees" ADD COLUMN "accountDeactivated" BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE "employees" ADD COLUMN "deactivatedAt" timestamp(3);
ALTER TABLE "employees" ADD COLUMN "deactivatedByUserId" TEXT;

ALTER TABLE "employees" ADD CONSTRAINT "employees_deactivatedByUserId_fkey" FOREIGN KEY ("deactivatedByUserId") REFERENCES "users"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- Preserve the OLD behavior for anyone ALREADY offboarded: under the previous rule status==OFFBOARDED blocked
-- login, so mark every currently-OFFBOARDED employee deactivated — this deploy must NOT silently restore their
-- access. Fresh completions AFTER this deploy stay active until HR clicks Deactivate. (deactivatedByUserId is
-- left null: the migration, not a person, set these.)
UPDATE "employees" SET "accountDeactivated" = true, "deactivatedAt" = now() WHERE "status" = 'OFFBOARDED';
