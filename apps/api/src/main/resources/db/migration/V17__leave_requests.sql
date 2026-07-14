-- V17: leave requests (§8b). Additive.
--   A credentialed employee requests time off; it routes to the Manager on the employee's onboarding-HR's
--   team (the resolved approver, stored as managerUserId — mirrors approval_requests). PENDING ->
--   APPROVED/REJECTED, or the owner cancels while PENDING (-> CANCELLED). NO leave balances in v1.
--   startDate/endDate are calendar DATEs; timestamps are TIMESTAMP(3) UTC (the app-wide Prisma-style).

CREATE TYPE "LeaveType" AS ENUM ('CASUAL', 'SICK', 'UNPAID');
CREATE TYPE "LeaveStatus" AS ENUM ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED');

-- Manager bell on a leave submission (§8b). Additive enum value; NOT used within this migration (Postgres
-- forbids using a freshly added enum value in the same transaction, but we only add it here).
ALTER TYPE "NotificationType" ADD VALUE IF NOT EXISTS 'LEAVE_REQUESTED';

CREATE TABLE "leave_requests" (
    "id" TEXT NOT NULL,
    "employeeId" TEXT NOT NULL,
    "companyId" TEXT NOT NULL,       -- denormalized for the tenant filter + indexes
    "managerUserId" TEXT NOT NULL,   -- resolved approver: employee.onboardingHr -> that HR's team -> manager
    "startDate" DATE NOT NULL,
    "endDate" DATE NOT NULL,
    "leaveType" "LeaveType" NOT NULL,
    "reason" TEXT NOT NULL,
    "status" "LeaveStatus" NOT NULL DEFAULT 'PENDING',
    "decisionNote" TEXT,
    "decidedAt" TIMESTAMP(3),
    "decidedByUserId" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "leave_requests_pkey" PRIMARY KEY ("id")
);

-- An employee's own requests, newest first.
CREATE INDEX "leave_employee_time_idx" ON "leave_requests"("employeeId", "createdAt" DESC);
-- The Manager's queue: his requests by status, newest first.
CREATE INDEX "leave_manager_status_time_idx" ON "leave_requests"("managerUserId", "status", "createdAt" DESC);
-- Tenant-scoped queries.
CREATE INDEX "leave_company_time_idx" ON "leave_requests"("companyId", "createdAt" DESC);

ALTER TABLE "leave_requests" ADD CONSTRAINT "leave_requests_employeeId_fkey"
    FOREIGN KEY ("employeeId") REFERENCES "employees"("id") ON DELETE CASCADE ON UPDATE CASCADE;
