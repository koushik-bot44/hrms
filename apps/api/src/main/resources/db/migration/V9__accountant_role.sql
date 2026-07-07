-- V9: add the top-level, cross-company READ-ONLY ACCOUNTANT staff role (§2/§6). Additive only —
-- a new value on the UserRole enum. The "exactly one Accountant" rule is enforced in the application
-- layer (provisioning rejects a second create); no schema constraint is required for it.
-- PostgreSQL 12+ allows ALTER TYPE ... ADD VALUE inside Flyway's transaction (the value is only added
-- here, never used in the same transaction). IF NOT EXISTS keeps the migration idempotent.

ALTER TYPE "UserRole" ADD VALUE IF NOT EXISTS 'ACCOUNTANT';
