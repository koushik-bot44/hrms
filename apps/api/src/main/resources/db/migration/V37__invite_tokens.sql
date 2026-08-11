-- V37: INVITE-LINK-ONLY onboarding door (ARCHITECTURE.md §3.2/§6). A candidate reaches the employee OTP flow
-- ONLY through a signed, expiring invite link HR emailed them; the door + BOTH OTP endpoints now require a valid
-- invite token. The token is a cryptographically-random OPAQUE value (not a JWT — revocable), stored HASHED
-- (SHA-256 hex, looked up by hash) so the raw value never rests in the DB. Additive only.
--
-- One ACTIVE token per employee: issuing a new one (invite, email-change re-invite, or HR resend) revokes the
-- previous (revokedAt set), so the old link stops working. A token dies at expiry (now + 7 days), on revoke, or
-- once the employee leaves an active onboarding status (APPROVED/REJECTED/OFFBOARDED — they move to workspace
-- credentials). usedAt is stamped on the first successful OTP verify but does NOT end validity (a candidate may
-- sign in repeatedly over days).

CREATE TABLE "employee_invite_tokens" (
    "id" TEXT NOT NULL,
    "employeeId" TEXT NOT NULL,
    -- SHA-256 hex of the opaque token (the raw value is emailed once, never stored).
    "tokenHash" TEXT NOT NULL,
    "expiresAt" timestamp(3) NOT NULL,
    -- First successful OTP verify (informational; does NOT gate validity).
    "usedAt" timestamp(3),
    -- Set when superseded (a newer token issued) — the one-active-token invariant.
    "revokedAt" timestamp(3),
    "createdAt" timestamp(3) NOT NULL DEFAULT now(),
    CONSTRAINT "employee_invite_tokens_pkey" PRIMARY KEY ("id")
);

-- Lookup is BY hash (the validate + OTP endpoints hash the presented token and find the row).
CREATE UNIQUE INDEX "employee_invite_tokens_tokenHash_key" ON "employee_invite_tokens"("tokenHash");
CREATE INDEX "employee_invite_tokens_employeeId_idx" ON "employee_invite_tokens"("employeeId");

ALTER TABLE "employee_invite_tokens" ADD CONSTRAINT "employee_invite_tokens_employeeId_fkey"
    FOREIGN KEY ("employeeId") REFERENCES "employees"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- Backfill: every currently-INVITED employee gets a token row so the data model is consistent AND the
-- one-active-token invariant holds. This is issued SILENTLY (no email) — the raw value is not recoverable, so
-- these placeholders are never usable; HR RESENDS to deliver a fresh, usable link (which revokes the placeholder).
-- This deliberately avoids a surprise mass email. Count affected = current INVITED employees (0 on a fresh DB).
-- gen_random_uuid() is core Postgres (>=13); the NOT EXISTS guard makes the statement safe to re-run.
INSERT INTO "employee_invite_tokens" ("id", "employeeId", "tokenHash", "expiresAt", "createdAt")
SELECT md5(gen_random_uuid()::text) || md5(gen_random_uuid()::text || e."id"),
       e."id",
       md5(gen_random_uuid()::text) || md5(e."id" || gen_random_uuid()::text),
       now() + interval '7 days',
       now()
FROM "employees" e
WHERE e."status" = 'INVITED'
  AND NOT EXISTS (
    SELECT 1 FROM "employee_invite_tokens" t
    WHERE t."employeeId" = e."id" AND t."revokedAt" IS NULL AND t."expiresAt" > now()
  );
