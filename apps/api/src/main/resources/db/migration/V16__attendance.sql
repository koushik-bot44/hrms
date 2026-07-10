-- V16: attendance — clock in / clock out (§8a). Additive.
--   One AttendanceSession per clock-in; clockOutAt NULL = still open. Times are UTC (timestamptz);
--   display + day grouping + totals are computed in Asia/Kolkata by the app. The partial unique index
--   enforces "at most one OPEN session per employee" at the DB level, alongside the service check, so a
--   double-click can't create two open sessions (the violation is surfaced as a clean 409).
CREATE TABLE "attendance_sessions" (
    "id" TEXT NOT NULL,
    "employeeId" TEXT NOT NULL,
    "companyId" TEXT NOT NULL, -- denormalized for the tenant filter + manager-scope queries
    "clockInAt" TIMESTAMPTZ NOT NULL,
    "clockOutAt" TIMESTAMPTZ,
    "createdAt" TIMESTAMPTZ NOT NULL DEFAULT now(),
    "updatedAt" TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT "attendance_sessions_pkey" PRIMARY KEY ("id")
);

-- At most ONE open session per employee (concurrency-safe; complements the service-level check).
CREATE UNIQUE INDEX "attendance_one_open_per_employee"
    ON "attendance_sessions"("employeeId") WHERE "clockOutAt" IS NULL;
-- History (an employee's own sessions, newest first) + the manager's tenant-scoped queries/feed.
CREATE INDEX "attendance_employee_time_idx" ON "attendance_sessions"("employeeId", "clockInAt" DESC);
CREATE INDEX "attendance_company_time_idx" ON "attendance_sessions"("companyId", "clockInAt" DESC);

ALTER TABLE "attendance_sessions" ADD CONSTRAINT "attendance_sessions_employeeId_fkey"
    FOREIGN KEY ("employeeId") REFERENCES "employees"("id") ON DELETE CASCADE ON UPDATE CASCADE;
