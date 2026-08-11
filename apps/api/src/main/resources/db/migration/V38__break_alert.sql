-- V38: LONG-OPEN-BREAK HR alert (ARCHITECTURE.md §8a). A scheduled scan notifies an employee's onboarding HR
-- when one of their breaks has stayed OPEN (never ended) longer than BREAK_ALERT_MINUTES (default 35). The new
-- `alertSentAt` column dedupes — a break is alerted exactly ONCE (the scan filters `alertSentAt IS NULL`), so an
-- employee who never ends a break is not re-notified on every scan. Additive only.

ALTER TABLE "attendance_breaks" ADD COLUMN "alertSentAt" TIMESTAMPTZ;

-- Durable notification kind for the HR trail (push + email are the live channels; HR has no bell feed yet).
ALTER TYPE "NotificationType" ADD VALUE IF NOT EXISTS 'EMPLOYEE_LONG_BREAK';
