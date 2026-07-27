-- V25: ARCHIVE internal mail (§8). Additive. Mirrors V24 (thread_stars).
--   A per-user, thread-level state: one row per (threadId, accountId) means that account has ARCHIVED that
--   conversation. Row-exists = archived; unarchive deletes the row. Unlike a star, an archived thread is
--   HIDDEN FROM THAT USER'S INBOX (the inbox query excludes archived threads) — but it is NOT deleted: it
--   stays in the Archive view, in Sent/Search/Starred, and normally for every other participant. accountId is
--   the archiver's globally-unique account id (a User id or an Employee id — the same id the mail service
--   resolves via senderAccountId / recipientAccountId).

CREATE TABLE "thread_archives" (
    "id" TEXT NOT NULL,
    "threadId" TEXT NOT NULL,
    "accountId" TEXT NOT NULL,           -- the archiver (User.id or Employee.id; globally unique)
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "thread_archives_pkey" PRIMARY KEY ("id")
);

-- At most one archive per (thread, owner) — makes archive idempotent and unarchive a clean delete.
CREATE UNIQUE INDEX "thread_archives_thread_account_key" ON "thread_archives"("threadId", "accountId");
-- "the archived threads for this account" (the Archive view, the per-row archived lookup, and the
-- inbox NOT-EXISTS exclusion).
CREATE INDEX "thread_archives_account_idx" ON "thread_archives"("accountId");
