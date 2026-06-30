-- IHRMS baseline schema (ARCHITECTURE.md §4/§5/§7). Reproduces the FINAL state of the
-- archived Prisma migrations (tag archive/ihrms-node) EXACTLY: same table/column names
-- (quoted camelCase), native PG enum types, JSONB columns, timestamp(3), and FK rules.

-- ---------------------------------------------------------------------------
-- Enums (native PG enum types; values match the canonical Java enums)
-- ---------------------------------------------------------------------------
CREATE TYPE "UserRole" AS ENUM ('SUPER_ADMIN', 'COMPANY_ADMIN', 'HR', 'MANAGER');
CREATE TYPE "EmployeeStatus" AS ENUM ('INVITED', 'IN_PROGRESS', 'SUBMITTED', 'HR_VERIFIED', 'APPROVED', 'REJECTED');
CREATE TYPE "SectionKey" AS ENUM ('PERSONAL', 'BACKGROUND', 'GOVERNMENT');
CREATE TYPE "SectionStatus" AS ENUM ('DRAFT', 'SUBMITTED', 'VERIFIED', 'REJECTED');
CREATE TYPE "DocumentType" AS ENUM ('EXPERIENCE_LETTER', 'PAN', 'AADHAAR', 'BGV_DOCUMENT', 'OTHER');
CREATE TYPE "DocumentStatus" AS ENUM ('PENDING', 'UPLOADED', 'VERIFIED', 'REJECTED');
CREATE TYPE "ApprovalStatus" AS ENUM ('PENDING', 'APPROVED', 'REJECTED');
CREATE TYPE "NotificationType" AS ENUM ('EMPLOYEE_ONBOARDED', 'EMPLOYEE_SUBMITTED', 'APPROVAL_REQUESTED', 'EMPLOYEE_APPROVED', 'EMPLOYEE_REJECTED');

-- ---------------------------------------------------------------------------
-- Tables
-- ---------------------------------------------------------------------------
CREATE TABLE "users" (
    "id" TEXT NOT NULL,
    "email" TEXT NOT NULL,
    "name" TEXT NOT NULL,
    "role" "UserRole" NOT NULL,
    "companyId" TEXT,
    "teamId" TEXT,
    "passwordHash" TEXT,
    "status" TEXT NOT NULL DEFAULT 'ACTIVE',
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,
    CONSTRAINT "users_pkey" PRIMARY KEY ("id")
);

CREATE TABLE "employees" (
    "id" TEXT NOT NULL,
    "employeeCode" TEXT NOT NULL,
    "email" TEXT NOT NULL,
    "companyId" TEXT NOT NULL,
    "onboardingHrId" TEXT NOT NULL,
    "status" "EmployeeStatus" NOT NULL DEFAULT 'INVITED',
    "otpHash" TEXT,
    "otpExpiresAt" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,
    CONSTRAINT "employees_pkey" PRIMARY KEY ("id")
);

CREATE TABLE "companies" (
    "id" TEXT NOT NULL,
    "name" TEXT NOT NULL,
    "code" TEXT NOT NULL,
    "status" TEXT NOT NULL DEFAULT 'ACTIVE',
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,
    CONSTRAINT "companies_pkey" PRIMARY KEY ("id")
);

CREATE TABLE "teams" (
    "id" TEXT NOT NULL,
    "companyId" TEXT NOT NULL,
    "name" TEXT NOT NULL,
    "hrUserId" TEXT,
    "managerUserId" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,
    CONSTRAINT "teams_pkey" PRIMARY KEY ("id")
);

CREATE TABLE "profile_sections" (
    "id" TEXT NOT NULL,
    "employeeId" TEXT NOT NULL,
    "key" "SectionKey" NOT NULL,
    "data" JSONB,
    "status" "SectionStatus" NOT NULL DEFAULT 'DRAFT',
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,
    CONSTRAINT "profile_sections_pkey" PRIMARY KEY ("id")
);

CREATE TABLE "documents" (
    "id" TEXT NOT NULL,
    "employeeId" TEXT NOT NULL,
    "sectionKey" "SectionKey" NOT NULL,
    "docType" "DocumentType" NOT NULL,
    "fileName" TEXT NOT NULL,
    "storageKey" TEXT NOT NULL,
    "mimeType" TEXT NOT NULL,
    "sha256" TEXT,
    "status" "DocumentStatus" NOT NULL DEFAULT 'UPLOADED',
    "uploadedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "documents_pkey" PRIMARY KEY ("id")
);

CREATE TABLE "approval_requests" (
    "id" TEXT NOT NULL,
    "employeeId" TEXT NOT NULL,
    "hrUserId" TEXT NOT NULL,
    "managerUserId" TEXT NOT NULL,
    "teamId" TEXT NOT NULL,
    "status" "ApprovalStatus" NOT NULL DEFAULT 'PENDING',
    "note" TEXT,
    "submittedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "decidedAt" TIMESTAMP(3),
    CONSTRAINT "approval_requests_pkey" PRIMARY KEY ("id")
);

CREATE TABLE "notifications" (
    "id" TEXT NOT NULL,
    "recipientUserId" TEXT NOT NULL,
    "type" "NotificationType" NOT NULL,
    "employeeId" TEXT,
    "read" BOOLEAN NOT NULL DEFAULT false,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "notifications_pkey" PRIMARY KEY ("id")
);

CREATE TABLE "audit_logs" (
    "id" TEXT NOT NULL,
    "companyId" TEXT,
    "actorType" TEXT NOT NULL,
    "actorId" TEXT,
    "action" TEXT NOT NULL,
    "targetType" TEXT,
    "targetId" TEXT,
    "metadata" JSONB,
    "ipAddress" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "audit_logs_pkey" PRIMARY KEY ("id")
);

CREATE TABLE "employee_code_sequences" (
    "id" TEXT NOT NULL,
    "companyId" TEXT NOT NULL,
    "lastSeq" INTEGER NOT NULL DEFAULT 0,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,
    CONSTRAINT "employee_code_sequences_pkey" PRIMARY KEY ("id")
);

-- ---------------------------------------------------------------------------
-- Indexes
-- ---------------------------------------------------------------------------
CREATE UNIQUE INDEX "users_email_key" ON "users"("email");
CREATE INDEX "users_companyId_idx" ON "users"("companyId");
CREATE INDEX "users_teamId_idx" ON "users"("teamId");
CREATE UNIQUE INDEX "employees_employeeCode_key" ON "employees"("employeeCode");
CREATE INDEX "employees_companyId_idx" ON "employees"("companyId");
CREATE INDEX "employees_onboardingHrId_idx" ON "employees"("onboardingHrId");
CREATE UNIQUE INDEX "companies_code_key" ON "companies"("code");
CREATE INDEX "teams_companyId_idx" ON "teams"("companyId");
CREATE INDEX "teams_hrUserId_idx" ON "teams"("hrUserId");
CREATE INDEX "teams_managerUserId_idx" ON "teams"("managerUserId");
CREATE INDEX "profile_sections_employeeId_idx" ON "profile_sections"("employeeId");
CREATE UNIQUE INDEX "profile_sections_employeeId_key_key" ON "profile_sections"("employeeId", "key");
CREATE INDEX "documents_employeeId_idx" ON "documents"("employeeId");
CREATE INDEX "documents_employeeId_sectionKey_idx" ON "documents"("employeeId", "sectionKey");
CREATE INDEX "approval_requests_employeeId_idx" ON "approval_requests"("employeeId");
CREATE INDEX "approval_requests_managerUserId_idx" ON "approval_requests"("managerUserId");
CREATE INDEX "approval_requests_teamId_idx" ON "approval_requests"("teamId");
CREATE INDEX "notifications_recipientUserId_idx" ON "notifications"("recipientUserId");
CREATE INDEX "notifications_employeeId_idx" ON "notifications"("employeeId");
CREATE INDEX "audit_logs_companyId_idx" ON "audit_logs"("companyId");
CREATE INDEX "audit_logs_createdAt_idx" ON "audit_logs"("createdAt");
CREATE INDEX "audit_logs_targetType_targetId_idx" ON "audit_logs"("targetType", "targetId");
CREATE UNIQUE INDEX "employee_code_sequences_companyId_key" ON "employee_code_sequences"("companyId");

-- ---------------------------------------------------------------------------
-- Foreign keys (final ON DELETE rules; teams assignees SET NULL)
-- ---------------------------------------------------------------------------
ALTER TABLE "users" ADD CONSTRAINT "users_companyId_fkey" FOREIGN KEY ("companyId") REFERENCES "companies"("id") ON DELETE SET NULL ON UPDATE CASCADE;
ALTER TABLE "users" ADD CONSTRAINT "users_teamId_fkey" FOREIGN KEY ("teamId") REFERENCES "teams"("id") ON DELETE SET NULL ON UPDATE CASCADE;
ALTER TABLE "employees" ADD CONSTRAINT "employees_companyId_fkey" FOREIGN KEY ("companyId") REFERENCES "companies"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "employees" ADD CONSTRAINT "employees_onboardingHrId_fkey" FOREIGN KEY ("onboardingHrId") REFERENCES "users"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "teams" ADD CONSTRAINT "teams_companyId_fkey" FOREIGN KEY ("companyId") REFERENCES "companies"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "teams" ADD CONSTRAINT "teams_hrUserId_fkey" FOREIGN KEY ("hrUserId") REFERENCES "users"("id") ON DELETE SET NULL ON UPDATE CASCADE;
ALTER TABLE "teams" ADD CONSTRAINT "teams_managerUserId_fkey" FOREIGN KEY ("managerUserId") REFERENCES "users"("id") ON DELETE SET NULL ON UPDATE CASCADE;
ALTER TABLE "profile_sections" ADD CONSTRAINT "profile_sections_employeeId_fkey" FOREIGN KEY ("employeeId") REFERENCES "employees"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "documents" ADD CONSTRAINT "documents_employeeId_fkey" FOREIGN KEY ("employeeId") REFERENCES "employees"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "approval_requests" ADD CONSTRAINT "approval_requests_employeeId_fkey" FOREIGN KEY ("employeeId") REFERENCES "employees"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "approval_requests" ADD CONSTRAINT "approval_requests_hrUserId_fkey" FOREIGN KEY ("hrUserId") REFERENCES "users"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "approval_requests" ADD CONSTRAINT "approval_requests_managerUserId_fkey" FOREIGN KEY ("managerUserId") REFERENCES "users"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "approval_requests" ADD CONSTRAINT "approval_requests_teamId_fkey" FOREIGN KEY ("teamId") REFERENCES "teams"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "notifications" ADD CONSTRAINT "notifications_recipientUserId_fkey" FOREIGN KEY ("recipientUserId") REFERENCES "users"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "notifications" ADD CONSTRAINT "notifications_employeeId_fkey" FOREIGN KEY ("employeeId") REFERENCES "employees"("id") ON DELETE SET NULL ON UPDATE CASCADE;
ALTER TABLE "employee_code_sequences" ADD CONSTRAINT "employee_code_sequences_companyId_fkey" FOREIGN KEY ("companyId") REFERENCES "companies"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
