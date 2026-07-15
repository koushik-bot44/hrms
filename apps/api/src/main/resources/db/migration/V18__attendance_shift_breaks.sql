-- V18: attendance v2 — overnight shift + late detection + breaks (§8a). Additive.
--   Sessions gain a persisted shift-day (19:00->04:00, attributed to the day the shift STARTED) + late flag.
--   Breaks live within an OPEN session and are EXCLUDED from worked time; at most one open break per session
--   (partial unique index, like the open-session one). Times are UTC (timestamptz); IST for display.

-- Breaks -------------------------------------------------------------------
CREATE TABLE "attendance_breaks" (
    "id" TEXT NOT NULL,
    "sessionId" TEXT NOT NULL,
    "companyId" TEXT NOT NULL,   -- denormalized for the tenant filter
    "breakStartAt" TIMESTAMPTZ NOT NULL,
    "breakEndAt" TIMESTAMPTZ,    -- NULL = still on break (open)
    "createdAt" TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT "attendance_breaks_pkey" PRIMARY KEY ("id")
);
-- At most ONE open break per session (concurrency-safe; complements the service-level check).
CREATE UNIQUE INDEX "attendance_one_open_break_per_session"
    ON "attendance_breaks"("sessionId") WHERE "breakEndAt" IS NULL;
CREATE INDEX "attendance_breaks_session_idx" ON "attendance_breaks"("sessionId");
ALTER TABLE "attendance_breaks" ADD CONSTRAINT "attendance_breaks_sessionId_fkey"
    FOREIGN KEY ("sessionId") REFERENCES "attendance_sessions"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- Shift-day + late on the session -----------------------------------------
ALTER TABLE "attendance_sessions" ADD COLUMN "shiftDate" DATE;
ALTER TABLE "attendance_sessions" ADD COLUMN "isLate" BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE "attendance_sessions" ADD COLUMN "lateMinutes" INTEGER;

-- Backfill shift_date: IST date of clock-in, minus one day when IST time < 04:00 (the shift-end cutoff).
UPDATE "attendance_sessions"
SET "shiftDate" = (("clockInAt" AT TIME ZONE 'Asia/Kolkata') - INTERVAL '4 hours')::date;

-- Backfill late: the FIRST clock-in of each (employee, shift_date) that is after 19:20 IST of that shift-day.
WITH ranked AS (
    SELECT "id",
           "clockInAt",
           ROW_NUMBER() OVER (PARTITION BY "employeeId", "shiftDate" ORDER BY "clockInAt") AS rn,
           (("shiftDate" + TIME '19:20') AT TIME ZONE 'Asia/Kolkata') AS threshold
    FROM "attendance_sessions"
)
UPDATE "attendance_sessions" s
SET "isLate" = true,
    "lateMinutes" = GREATEST(0, FLOOR(EXTRACT(EPOCH FROM (s."clockInAt" - r.threshold)) / 60))::int
FROM ranked r
WHERE s."id" = r."id" AND r.rn = 1 AND s."clockInAt" > r.threshold;

ALTER TABLE "attendance_sessions" ALTER COLUMN "shiftDate" SET NOT NULL;
CREATE INDEX "attendance_employee_shiftdate_idx" ON "attendance_sessions"("employeeId", "shiftDate");
