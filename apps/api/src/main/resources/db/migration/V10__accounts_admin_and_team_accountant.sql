-- V10: split the accountant concept into two roles (§2/§6).
--   * ACCOUNTS_ADMIN (NEW enum value) = the cross-company, read-only SINGLETON — the behaviour the
--     existing ACCOUNTANT user has today. The existing user is data-migrated to it in V11 (a separate
--     migration, because PostgreSQL forbids USING a newly-added enum value in the same transaction that
--     adds it).
--   * ACCOUNTANT (kept value, repurposed) = a NEW team-scoped, read-only role going forward.
-- Also add the team's Accountant slot. Additive only — no value renamed in place, no column dropped.

ALTER TYPE "UserRole" ADD VALUE IF NOT EXISTS 'ACCOUNTS_ADMIN';

-- The team's Accountant slot (nullable; existing teams may lack one and keep working, §2).
ALTER TABLE "teams" ADD COLUMN "accountantUserId" TEXT;
ALTER TABLE "teams"
    ADD CONSTRAINT "teams_accountantUserId_fkey"
    FOREIGN KEY ("accountantUserId") REFERENCES "users"("id") ON DELETE SET NULL ON UPDATE CASCADE;
