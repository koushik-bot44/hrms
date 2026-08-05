-- V33: offboarding stage 3 — LETTERS + COMPLETION (ARCHITECTURE.md §3.6 stage 3). The employee requests the
-- Relieving + Experience letters (reusing the HR/Accounts document-requests machinery, routed to the case HR);
-- HR then COMPLETES the offboarding: the case becomes COMPLETED and the employee OFFBOARDED (login disabled,
-- record retained, dashboards reconciled). Additive only. New enum values are USED only at runtime (never in
-- this migration) -> transaction-safe (see V17/V30/V31/V32).

-- The terminal employee state + the terminal case state.
ALTER TYPE "EmployeeStatus" ADD VALUE IF NOT EXISTS 'OFFBOARDED';
ALTER TYPE "OffboardingStatus" ADD VALUE IF NOT EXISTS 'COMPLETED';

-- The two HR-routed letter request types (all existing accountant-routed types unchanged).
ALTER TYPE "RequestType" ADD VALUE IF NOT EXISTS 'RELIEVING_LETTER';
ALTER TYPE "RequestType" ADD VALUE IF NOT EXISTS 'EXPERIENCE_LETTER';

-- The HR completion decision on the case (who/when/note); null until completed.
ALTER TABLE "offboarding_cases" ADD COLUMN "completedByUserId" TEXT;
ALTER TABLE "offboarding_cases" ADD COLUMN "completedAt" timestamp(3);
ALTER TABLE "offboarding_cases" ADD COLUMN "completionNote" TEXT;

ALTER TABLE "offboarding_cases" ADD CONSTRAINT "offboarding_cases_completedByUserId_fkey" FOREIGN KEY ("completedByUserId") REFERENCES "users"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
