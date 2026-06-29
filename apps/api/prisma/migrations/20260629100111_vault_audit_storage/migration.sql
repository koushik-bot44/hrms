/*
  Warnings:

  - Added the required column `provisioningClass` to the `documents` table without a default value. This is not possible if the table is not empty.

*/
-- CreateEnum
CREATE TYPE "ProvisioningClass" AS ENUM ('ISSUED', 'COLLECTED', 'REFERENCED');

-- AlterTable
ALTER TABLE "documents" ADD COLUMN     "accessPolicy" TEXT NOT NULL DEFAULT 'PRIVATE',
ADD COLUMN     "mimeType" TEXT,
ADD COLUMN     "providerRef" TEXT,
ADD COLUMN     "provisioningClass" "ProvisioningClass" NOT NULL,
ADD COLUMN     "sha256" TEXT,
ADD COLUMN     "storageKey" TEXT,
ADD COLUMN     "supersededById" TEXT,
ALTER COLUMN "uniqueId" DROP NOT NULL,
ALTER COLUMN "typeCode" DROP NOT NULL,
ALTER COLUMN "class" DROP NOT NULL,
ALTER COLUMN "status" DROP NOT NULL,
ALTER COLUMN "status" DROP DEFAULT,
ALTER COLUMN "title" DROP NOT NULL;

-- CreateTable
CREATE TABLE "document_versions" (
    "id" TEXT NOT NULL,
    "documentId" TEXT NOT NULL,
    "version" INTEGER NOT NULL,
    "sha256" TEXT NOT NULL,
    "mimeType" TEXT,
    "storageKey" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "document_versions_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "document_sequences" (
    "id" TEXT NOT NULL,
    "entityId" TEXT NOT NULL,
    "typeCode" TEXT NOT NULL,
    "year" INTEGER NOT NULL,
    "lastSeq" INTEGER NOT NULL DEFAULT 0,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "document_sequences_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "audit_events" (
    "id" TEXT NOT NULL,
    "action" TEXT NOT NULL,
    "actorType" TEXT NOT NULL,
    "actorId" TEXT,
    "targetType" TEXT,
    "targetId" TEXT,
    "ipAddress" TEXT,
    "metadata" JSONB,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "audit_events_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE INDEX "document_versions_documentId_idx" ON "document_versions"("documentId");

-- CreateIndex
CREATE UNIQUE INDEX "document_versions_documentId_version_key" ON "document_versions"("documentId", "version");

-- CreateIndex
CREATE UNIQUE INDEX "document_sequences_entityId_typeCode_year_key" ON "document_sequences"("entityId", "typeCode", "year");

-- CreateIndex
CREATE INDEX "audit_events_targetType_targetId_idx" ON "audit_events"("targetType", "targetId");

-- CreateIndex
CREATE INDEX "audit_events_createdAt_idx" ON "audit_events"("createdAt");

-- CreateIndex
CREATE INDEX "documents_provisioningClass_idx" ON "documents"("provisioningClass");

-- AddForeignKey
ALTER TABLE "documents" ADD CONSTRAINT "documents_supersededById_fkey" FOREIGN KEY ("supersededById") REFERENCES "documents"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "document_versions" ADD CONSTRAINT "document_versions_documentId_fkey" FOREIGN KEY ("documentId") REFERENCES "documents"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
