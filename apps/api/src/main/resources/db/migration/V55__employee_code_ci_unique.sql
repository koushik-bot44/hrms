-- V55: CASE-INSENSITIVE uniqueness for employee codes. Every application-level check compares codes with
-- upper() (minted codes are already uppercase; §3.2's HR-ENTERED existing-employee IDs are free-case), but
-- V1's unique index is exact-case — so a compound race could approve "EMP-07" and "emp-07" as two records
-- the app then treats as the same "in use" ID. This functional index makes the database agree with the app.
-- Safe on existing data: minted codes are uppercase-only and entered IDs are checked before every write.

CREATE UNIQUE INDEX "employees_employeeCode_ci_key" ON "employees" (upper("employeeCode"));
