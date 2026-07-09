-- V13: internal-mail Stage 3 — threading, per-user soft delete, reply chain (§8). Additive.
--   * messages.threadId — conversation grouping key. Backfilled so each EXISTING message becomes its
--     own thread (threadId = id), then made NOT NULL. New threads mint a fresh id; replies reuse it.
--   * messages.parentMessageId — the reply chain (nullable; null for a thread's first message).
--   * messages.senderDeletedAt — the sender's per-user soft-hide (row is never destroyed).
--   * message_recipients.deletedAt — the recipient's per-user soft-hide.
-- No column is dropped; nothing is destroyed.

-- --- messages.threadId (backfill each existing message into its own thread -> NOT NULL) ------
ALTER TABLE "messages" ADD COLUMN "threadId" TEXT;
UPDATE "messages" SET "threadId" = "id" WHERE "threadId" IS NULL;
ALTER TABLE "messages" ALTER COLUMN "threadId" SET NOT NULL;
-- Thread reads (list a thread's messages chronologically; group inbox/sent/search by thread).
CREATE INDEX "messages_threadId_createdAt_idx" ON "messages"("threadId", "createdAt");

-- --- messages.parentMessageId (reply chain; SET NULL if a parent ever went away) --------------
ALTER TABLE "messages" ADD COLUMN "parentMessageId" TEXT;
ALTER TABLE "messages" ADD CONSTRAINT "messages_parentMessageId_fkey"
    FOREIGN KEY ("parentMessageId") REFERENCES "messages"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- --- per-user soft delete (never destroy the row) ---------------------------------------------
ALTER TABLE "messages" ADD COLUMN "senderDeletedAt" TIMESTAMP(3);
ALTER TABLE "message_recipients" ADD COLUMN "deletedAt" TIMESTAMP(3);
-- Unread-thread lookup + inbox visibility filter (recipient side, excluding soft-deleted).
CREATE INDEX "message_recipients_unread_idx"
    ON "message_recipients"("recipientUserId", "readAt", "deletedAt");
-- Sent-side visibility filter (a user's own sent messages, excluding soft-deleted).
CREATE INDEX "messages_sender_notdeleted_idx" ON "messages"("senderUserId", "senderDeletedAt");
