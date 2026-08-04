-- V30: post-approval agreements (ARCHITECTURE.md §Agreements). After an employee is APPROVED, HR may SEND a
-- standard pack of three company agreements — AUP, NDA & Non-Compete, and Notice Period Conduct Guidelines.
-- The employee reads each in full, fills the few blanks, signs, and submits; the system renders a PDF per
-- agreement (template text + substitutions + filled values + signature + date) and stores it under the
-- employee record. There is no verification loop. Additive only — nothing is dropped.

-- The three standard agreements, and their lifecycle. Native PG enum types (matched to the Java enum simple
-- names for Hibernate's NAMED_ENUM mapping), consistent with every other enum in the schema.
CREATE TYPE "EmployeeAgreementType" AS ENUM ('AUP', 'NDA', 'NOTICE_PERIOD');
CREATE TYPE "AgreementStatus" AS ENUM ('PENDING', 'COMPLETED');

-- A durable notification kind for the SENDING HR when an employee completes an agreement (the value is only
-- USED at runtime, never in this migration, so adding it here is transaction-safe — see V17).
ALTER TYPE "NotificationType" ADD VALUE IF NOT EXISTS 'AGREEMENT_COMPLETED';

-- One row per (employee, agreement type). Created PENDING when HR sends; flips to COMPLETED with a stored PDF
-- key when the employee submits. `sentByUserId` is the SENDING HR — it feeds {{HR_NAME}} on the NDA company
-- block and is the recipient of the completion notification.
CREATE TABLE "employee_agreements" (
    "id" TEXT NOT NULL,
    "employeeId" TEXT NOT NULL,
    "type" "EmployeeAgreementType" NOT NULL,
    "status" "AgreementStatus" NOT NULL DEFAULT 'PENDING',
    "sentAt" timestamp(3) NOT NULL DEFAULT now(),
    "sentByUserId" TEXT NOT NULL,
    "completedAt" timestamp(3),
    -- Server-side S3 key of the rendered PDF; null until the employee completes the agreement.
    "storageKey" TEXT,
    CONSTRAINT "employee_agreements_pkey" PRIMARY KEY ("id")
);

-- Idempotent send: an employee can only ever hold one row per agreement type (re-sending a completed pack is
-- rejected at the service, and this index is the hard backstop).
CREATE UNIQUE INDEX "employee_agreements_employeeId_type_key" ON "employee_agreements"("employeeId", "type");
CREATE INDEX "employee_agreements_employeeId_idx" ON "employee_agreements"("employeeId");

ALTER TABLE "employee_agreements" ADD CONSTRAINT "employee_agreements_employeeId_fkey" FOREIGN KEY ("employeeId") REFERENCES "employees"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "employee_agreements" ADD CONSTRAINT "employee_agreements_sentByUserId_fkey" FOREIGN KEY ("sentByUserId") REFERENCES "users"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- Aadhaar number, typed by the employee while completing the AUP acknowledgement. Sensitive (PAN-class, §6):
-- stored ENCRYPTED at rest via the same AES-256-GCM field converter PAN uses, masked in every DTO, and
-- revealable only via the existing audited reveal endpoint. Employee-level (a durable identity attribute),
-- not an agreement-row column — one consistent mask/reveal path alongside PAN. Ciphertext is stored as text.
ALTER TABLE "employees" ADD COLUMN "aadhaarNumber" TEXT;
