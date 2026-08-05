-- V31: offboarding lifecycle, stage 1 — the case + hierarchy approval (ARCHITECTURE.md §Offboarding). HR
-- initiates an offboarding case for an APPROVED employee; the platform HIERARCHY role approves or rejects it
-- (its ONLY write surface, a deliberate charter loosening). Documents (stage 2) and letters/completion
-- (stage 3) bolt on later. Additive only. Employee.status is NOT touched in this stage.

-- Case lifecycle. COMPLETED arrives in stage 3. Native PG enum type (matched to the Java enum simple name).
CREATE TYPE "OffboardingStatus" AS ENUM ('PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'CANCELLED');

-- Durable notification kinds (used only at runtime, never in this migration -> transaction-safe, see V17/V30):
-- the HIERARCHY user on initiate; the initiating HR on the hierarchy decision.
ALTER TYPE "NotificationType" ADD VALUE IF NOT EXISTS 'OFFBOARDING_INITIATED';
ALTER TYPE "NotificationType" ADD VALUE IF NOT EXISTS 'OFFBOARDING_APPROVED';
ALTER TYPE "NotificationType" ADD VALUE IF NOT EXISTS 'OFFBOARDING_REJECTED';

CREATE TABLE "offboarding_cases" (
    "id" TEXT NOT NULL,
    "employeeId" TEXT NOT NULL,
    "status" "OffboardingStatus" NOT NULL DEFAULT 'PENDING_APPROVAL',
    "reason" TEXT NOT NULL,
    "lastWorkingDay" date NOT NULL,
    -- Who initiated (HR) and when.
    "initiatedByUserId" TEXT NOT NULL,
    "initiatedAt" timestamp(3) NOT NULL DEFAULT now(),
    -- The HIERARCHY decision (approve/reject); null until decided.
    "decidedByUserId" TEXT,
    "decidedAt" timestamp(3),
    "decisionNote" TEXT,
    -- HR cancellation (pre-completion); null unless cancelled.
    "cancelledByUserId" TEXT,
    "cancelledAt" timestamp(3),
    "cancelNote" TEXT,
    CONSTRAINT "offboarding_cases_pkey" PRIMARY KEY ("id")
);

-- At most ONE non-terminal case per employee (PENDING_APPROVAL or APPROVED). A rejected/cancelled case frees
-- the employee for a fresh case. Enforced in the service too; this partial unique index is the hard backstop.
CREATE UNIQUE INDEX "offboarding_cases_active_employee_key"
    ON "offboarding_cases"("employeeId")
    WHERE "status" IN ('PENDING_APPROVAL', 'APPROVED');
CREATE INDEX "offboarding_cases_status_idx" ON "offboarding_cases"("status");
CREATE INDEX "offboarding_cases_employeeId_idx" ON "offboarding_cases"("employeeId");

ALTER TABLE "offboarding_cases" ADD CONSTRAINT "offboarding_cases_employeeId_fkey" FOREIGN KEY ("employeeId") REFERENCES "employees"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "offboarding_cases" ADD CONSTRAINT "offboarding_cases_initiatedByUserId_fkey" FOREIGN KEY ("initiatedByUserId") REFERENCES "users"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "offboarding_cases" ADD CONSTRAINT "offboarding_cases_decidedByUserId_fkey" FOREIGN KEY ("decidedByUserId") REFERENCES "users"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "offboarding_cases" ADD CONSTRAINT "offboarding_cases_cancelledByUserId_fkey" FOREIGN KEY ("cancelledByUserId") REFERENCES "users"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
