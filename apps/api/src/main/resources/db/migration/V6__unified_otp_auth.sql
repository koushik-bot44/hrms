-- V6: unified authentication (ARCHITECTURE.md §6). ALL principals — staff (User) and employees —
-- sign in with full name + email + OTP; there are no more passwords. Employees already had
-- otpHash/otpExpiresAt; give the staff (users) table the same, so staff can receive an OTP too.
--
-- Additive + safe over existing rows:
--   * both columns are nullable (an OTP only exists between request and verify);
--   * users.passwordHash is intentionally KEPT (now dormant / break-glass) — never dropped.
ALTER TABLE "users" ADD COLUMN "otpHash" TEXT;
ALTER TABLE "users" ADD COLUMN "otpExpiresAt" TIMESTAMP(3);
