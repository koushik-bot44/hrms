-- V21: add the HIERARCHY role (§2/§6) — a top-level, cross-platform, READ-ONLY, AGGREGATES-ONLY
-- singleton (provisioned SUPER_ADMIN-only). Additive only: a new enum label, nothing renamed or dropped.
-- No data backfill is needed (brand-new role, no existing rows to convert), so a single migration
-- suffices — unlike the ACCOUNTANT -> ACCOUNTS_ADMIN split, which needed a separate backfill migration
-- because PostgreSQL forbids USING a newly-added enum value in the same transaction that adds it.

ALTER TYPE "UserRole" ADD VALUE IF NOT EXISTS 'HIERARCHY';
