-- New onboarding flow (ARCHITECTURE.md §3.2/§4/§5/§6). HR onboards with full name, designation and
-- date of joining; the unique employee ID is no longer minted at onboarding (it is allocated on
-- Manager approval), and employees authenticate with full name + email + OTP.
--
-- Additive + safe over existing rows:
--  * new columns are nullable in the DB (required at the DTO layer);
--  * employeeCode loses its NOT NULL (the unique index stays — Postgres allows multiple NULLs);
--  * employee.email gains a GLOBAL unique index (it is now the login handle).
ALTER TABLE "employees" ADD COLUMN "fullName" TEXT;
ALTER TABLE "employees" ADD COLUMN "designation" TEXT;
ALTER TABLE "employees" ADD COLUMN "dateOfJoining" DATE;

ALTER TABLE "employees" ALTER COLUMN "employeeCode" DROP NOT NULL;

CREATE UNIQUE INDEX "employees_email_key" ON "employees"("email");
