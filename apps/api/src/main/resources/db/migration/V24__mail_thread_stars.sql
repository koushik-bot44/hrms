-- V24: STARRED internal mail (§8). Additive.
--   A per-user, thread-level flag: one row per (threadId, accountId) means that account has starred that
--   conversation. Row-exists = starred; unstar deletes the row. accountId is the star owner's globally-unique
--   account id (a User id or an Employee id — the same id the mail service resolves via senderAccountId /
--   recipientAccountId). This is the first per-user-thread state (Archive + Labels will reuse the shape).

CREATE TABLE "thread_stars" (
    "id" TEXT NOT NULL,
    "threadId" TEXT NOT NULL,
    "accountId" TEXT NOT NULL,           -- the star owner (User.id or Employee.id; globally unique)
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "thread_stars_pkey" PRIMARY KEY ("id")
);

-- At most one star per (thread, owner) — makes star idempotent and unstar a clean delete.
CREATE UNIQUE INDEX "thread_stars_thread_account_key" ON "thread_stars"("threadId", "accountId");
-- "the starred threads for this account" (the Starred view + the per-row star lookup).
CREATE INDEX "thread_stars_account_idx" ON "thread_stars"("accountId");
