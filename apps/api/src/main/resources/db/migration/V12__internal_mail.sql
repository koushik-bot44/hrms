-- V12: internal-only mail foundation (§8). Additive.
--   * companies.mailDomain — the per-company mail domain. Backfilled from lower(code) so no existing
--     company lacks one, then made NOT NULL + unique.
--   * users.mailLocalPart — the mailbox local part (the mailbox address == the login email; the local
--     part is backfilled from the current email so existing accounts have one).
--   * messages / message_recipients — text messages fanned out to recipients with per-recipient read
--     state (child table so multi-recipient is possible later). No external delivery — DB rows only.

-- --- companies.mailDomain (backfill -> NOT NULL -> unique) ------------------
ALTER TABLE "companies" ADD COLUMN "mailDomain" TEXT;
UPDATE "companies" SET "mailDomain" = lower("code") WHERE "mailDomain" IS NULL;
ALTER TABLE "companies" ALTER COLUMN "mailDomain" SET NOT NULL;
CREATE UNIQUE INDEX "companies_mailDomain_key" ON "companies"("mailDomain");

-- --- users.mailLocalPart (backfill from the existing login email) -----------
ALTER TABLE "users" ADD COLUMN "mailLocalPart" TEXT;
UPDATE "users" SET "mailLocalPart" = split_part("email", '@', 1) WHERE "mailLocalPart" IS NULL;

-- --- messages ---------------------------------------------------------------
CREATE TABLE "messages" (
    "id" TEXT NOT NULL,
    "senderUserId" TEXT NOT NULL,
    "subject" TEXT NOT NULL,
    "body" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "messages_pkey" PRIMARY KEY ("id")
);
CREATE INDEX "messages_senderUserId_idx" ON "messages"("senderUserId");
-- CASCADE so removing a staff account (e.g. replacing the Accounts Admin) never gets FK-blocked.
ALTER TABLE "messages" ADD CONSTRAINT "messages_senderUserId_fkey"
    FOREIGN KEY ("senderUserId") REFERENCES "users"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- --- message_recipients (per-recipient read state) --------------------------
CREATE TABLE "message_recipients" (
    "id" TEXT NOT NULL,
    "messageId" TEXT NOT NULL,
    "recipientUserId" TEXT NOT NULL,
    "readAt" TIMESTAMP(3),
    CONSTRAINT "message_recipients_pkey" PRIMARY KEY ("id")
);
-- Inbox query: a recipient's rows, unread first / filtered by readAt.
CREATE INDEX "message_recipients_recipient_idx" ON "message_recipients"("recipientUserId", "readAt");
CREATE INDEX "message_recipients_messageId_idx" ON "message_recipients"("messageId");
ALTER TABLE "message_recipients" ADD CONSTRAINT "message_recipients_messageId_fkey"
    FOREIGN KEY ("messageId") REFERENCES "messages"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "message_recipients" ADD CONSTRAINT "message_recipients_recipientUserId_fkey"
    FOREIGN KEY ("recipientUserId") REFERENCES "users"("id") ON DELETE CASCADE ON UPDATE CASCADE;
