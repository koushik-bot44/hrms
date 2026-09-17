-- V54: HOW THE RECORD WAS OPENED (ARCHITECTURE.md §3.2). A NEW_HIRE gets the offer letter + selection email
-- and onboards themselves; an EXISTING_EMPLOYEE (already on the payroll, no IHRMS record yet) gets NO offer
-- and NO email — HR creates the record with the employee ID + official email the person already has, enters
-- Forms 1/3/4 and a scanned signature via /employees/{id}/onboarding, and approves directly (no verify step).
-- The entered ID lives in form2_info.data->>'employeeId' until approval copies it onto employees."employeeCode"
-- (which MUST stay NULL before then — a non-null code means "approved" to the read-only viewer roles, §6).
-- Additive only; every pre-existing row is a NEW_HIRE.

CREATE TYPE "OnboardingType" AS ENUM ('NEW_HIRE', 'EXISTING_EMPLOYEE');

ALTER TABLE "employees"
    ADD COLUMN "onboardingType" "OnboardingType" NOT NULL DEFAULT 'NEW_HIRE';
