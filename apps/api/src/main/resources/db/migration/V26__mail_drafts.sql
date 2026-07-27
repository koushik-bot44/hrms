-- V26: DRAFTS internal mail (§8). Additive.
--   An author-private, UNSENT composition. A draft is NOT a Message: it creates no delivery rows, no thread,
--   no inbox entry, and fires no notification. It holds whatever the author has entered so far — recipients
--   (as id lists; may be empty or point at not-currently-permitted accounts), subject/body (may be empty),
--   attachment ids (unbound "message_attachments" reused from the compose handshake), and — for a reply-draft
--   — the thread it would reply into. Save is permissive (no canSendMail, no required fields); canSendMail +
--   the normal validation run ONLY at POST /mail/drafts/{id}/send, which then removes the draft.

CREATE TABLE "mail_drafts" (
    "id" TEXT NOT NULL,
    "authorAccountId" TEXT NOT NULL,     -- the draft owner (User.id or Employee.id; globally unique)
    "subject" TEXT,                      -- may be empty/null (permissive)
    "body" TEXT,                         -- may be empty/null (permissive)
    "toIds" JSONB,                       -- recipient id lists as entered (may be empty / not-yet-permitted)
    "ccIds" JSONB,
    "bccIds" JSONB,
    "attachmentIds" JSONB,               -- ids of the author's unbound "message_attachments" draft uploads
    "replyToThreadId" TEXT,              -- set => a reply-draft; send goes through the reply path
    "replyAll" BOOLEAN NOT NULL DEFAULT false,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "mail_drafts_pkey" PRIMARY KEY ("id")
);

-- "this author's drafts, most-recently-edited first" (the Drafts list + author-scoped open/edit/send/discard).
CREATE INDEX "mail_drafts_author_updated_idx" ON "mail_drafts"("authorAccountId", "updatedAt" DESC);
