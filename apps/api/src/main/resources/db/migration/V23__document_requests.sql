-- V23: HR/Accounts Requests — Accounts side (§8d). Additive.
--   A credentialed employee asks their team's ACCOUNTANT for a document; it routes to
--   team.accountantUser (employee.onboardingHr -> that HR's team -> accountant), the resolved routee is
--   stored as accountantUserId (mirrors leave_requests.managerUserId). SUBMITTED -> IN_PROGRESS ->
--   RESOLVED, or the owner cancels while SUBMITTED (-> CANCELLED). The accountant uploads 1..N files to
--   resolve. Timestamps are TIMESTAMP(3) UTC (the app-wide Prisma-style).

CREATE TYPE "RequestType" AS ENUM ('PAYSLIP', 'SALARY_CERTIFICATE', 'FORM16', 'TAX_DOCUMENT', 'OTHER');
CREATE TYPE "RequestStatus" AS ENUM ('SUBMITTED', 'IN_PROGRESS', 'RESOLVED', 'CANCELLED');

CREATE TABLE "document_requests" (
    "id" TEXT NOT NULL,
    "employeeId" TEXT NOT NULL,
    "companyId" TEXT NOT NULL,           -- denormalized for the tenant filter + indexes
    "accountantUserId" TEXT NOT NULL,    -- resolved routee: employee.onboardingHr -> that HR's team -> accountant
    "requestType" "RequestType" NOT NULL,
    "note" TEXT,                         -- free-text, e.g. the period ("Jan-Mar 2026")
    "status" "RequestStatus" NOT NULL DEFAULT 'SUBMITTED',
    "resolveNote" TEXT,                  -- optional note the accountant adds on resolve
    "pickedUpAt" TIMESTAMP(3),
    "resolvedAt" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "document_requests_pkey" PRIMARY KEY ("id")
);

-- The fulfilment files (1..N per request). An unbound DRAFT (upload-url issued, not yet resolved) has a
-- NULL sha256; resolve re-reads the bytes, re-validates, and sets the real size + sha256 (bound = fulfilled).
CREATE TABLE "request_documents" (
    "id" TEXT NOT NULL,
    "requestId" TEXT NOT NULL,
    "fileName" TEXT NOT NULL,
    "contentType" TEXT NOT NULL,
    "sizeBytes" BIGINT NOT NULL,
    "storageKey" TEXT NOT NULL,
    "sha256" TEXT,                       -- NULL while an unbound draft; set on resolve/bind
    "uploadedByUserId" TEXT NOT NULL,    -- the accountant who uploaded it
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "request_documents_pkey" PRIMARY KEY ("id")
);

-- An employee's own requests, newest first.
CREATE INDEX "docreq_employee_time_idx" ON "document_requests"("employeeId", "createdAt" DESC);
-- The Accountant's queue: their requests by status, newest first.
CREATE INDEX "docreq_accountant_status_time_idx" ON "document_requests"("accountantUserId", "status", "createdAt" DESC);
-- Tenant-scoped queries.
CREATE INDEX "docreq_company_time_idx" ON "document_requests"("companyId", "createdAt" DESC);
-- A request's fulfilment files.
CREATE INDEX "reqdoc_request_idx" ON "request_documents"("requestId");

ALTER TABLE "document_requests" ADD CONSTRAINT "document_requests_employeeId_fkey"
    FOREIGN KEY ("employeeId") REFERENCES "employees"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "request_documents" ADD CONSTRAINT "request_documents_requestId_fkey"
    FOREIGN KEY ("requestId") REFERENCES "document_requests"("id") ON DELETE CASCADE ON UPDATE CASCADE;
