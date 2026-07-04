-- V7: reversible company archival (soft-delete) — ARCHITECTURE.md §2/§6/§7. A company gains the
-- DELETED status value (status stays a free TEXT column, so no enum change) plus who/when it was
-- archived. Additive + nullable; nothing is dropped. Restore clears these back to NULL.
ALTER TABLE "companies" ADD COLUMN "deletedAt" TIMESTAMP(3);
ALTER TABLE "companies" ADD COLUMN "deletedByUserId" TEXT;

ALTER TABLE "companies"
  ADD CONSTRAINT "companies_deletedByUserId_fkey"
  FOREIGN KEY ("deletedByUserId") REFERENCES "users"("id") ON DELETE SET NULL ON UPDATE CASCADE;
