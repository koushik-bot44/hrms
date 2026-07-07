-- V11: data-migrate the existing cross-company singleton from the OLD ACCOUNTANT meaning to the new
-- ACCOUNTS_ADMIN role (§2). Separate from V10 because PostgreSQL cannot USE a newly-added enum value in
-- the same transaction that added it — V10 committed 'ACCOUNTS_ADMIN', so it is usable here.
-- After this runs, no user carries the old meaning of ACCOUNTANT; the value now denotes the NEW
-- team-scoped role (a User with a teamId). Cross-company accountants had companyId = null.
UPDATE "users" SET "role" = 'ACCOUNTS_ADMIN' WHERE "role" = 'ACCOUNTANT';
