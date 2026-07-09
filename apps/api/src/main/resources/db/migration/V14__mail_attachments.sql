-- V14: internal-mail attachments (§8, Stage 4). Additive.
--   message_attachments — files carried by a mail message, stored in S3 via the same presigned
--   upload->confirm handshake as employee documents (sha256 recorded). "messageId" is NULL while the
--   attachment is a DRAFT (uploaded but not yet bound to a sent message); it is set when send/reply binds
--   it. Access is governed by the message's THREAD (participant-scoped), never by company scope.
CREATE TABLE "message_attachments" (
    "id" TEXT NOT NULL,
    "messageId" TEXT,
    "uploaderUserId" TEXT NOT NULL,
    "fileName" TEXT NOT NULL,
    "contentType" TEXT NOT NULL,
    "sizeBytes" BIGINT NOT NULL DEFAULT 0,
    "storageKey" TEXT NOT NULL,
    "sha256" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "message_attachments_pkey" PRIMARY KEY ("id")
);

-- Bound attachments are listed by message (and cascade-cleaned if a message is ever hard-deleted,
-- matching the existing mail FKs). Drafts (NULL messageId) are never FK-blocked.
CREATE INDEX "message_attachments_messageId_idx" ON "message_attachments"("messageId");
ALTER TABLE "message_attachments" ADD CONSTRAINT "message_attachments_messageId_fkey"
    FOREIGN KEY ("messageId") REFERENCES "messages"("id") ON DELETE CASCADE ON UPDATE CASCADE;
-- The uploader owns their drafts (only they may bind them); cascade if the account is removed.
ALTER TABLE "message_attachments" ADD CONSTRAINT "message_attachments_uploaderUserId_fkey"
    FOREIGN KEY ("uploaderUserId") REFERENCES "users"("id") ON DELETE CASCADE ON UPDATE CASCADE;
