-- V27: LABELS internal mail (§8). Additive.
--   Author-private, per-user TAGS for organizing conversations. mail_labels = the named tags (one owner
--   each); label_threads = the per-user, thread-level (labelId, threadId) assignments (mirrors the
--   thread_stars/thread_archives shape, plus a label FK). A label tags a WHOLE thread; applying it does NOT
--   move the thread out of Inbox — each label is just a VIEW of the conversations tagged with it. Names are
--   unique per author, case-insensitively; (labelId, threadId) is unique so applying is idempotent.

CREATE TABLE "mail_labels" (
    "id" TEXT NOT NULL,
    "authorAccountId" TEXT NOT NULL,     -- the label owner (User.id or Employee.id; globally unique)
    "name" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "mail_labels_pkey" PRIMARY KEY ("id")
);

-- One label name per author, case-insensitive (a duplicate create/rename is a 409).
CREATE UNIQUE INDEX "mail_labels_author_lower_name_key"
    ON "mail_labels"("authorAccountId", lower("name"));

CREATE TABLE "label_threads" (
    "id" TEXT NOT NULL,
    "labelId" TEXT NOT NULL,
    "threadId" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "label_threads_pkey" PRIMARY KEY ("id"),
    -- Deleting a label removes all of its assignments (the threads themselves are untouched).
    CONSTRAINT "label_threads_labelId_fkey" FOREIGN KEY ("labelId")
        REFERENCES "mail_labels"("id") ON DELETE CASCADE
);

-- At most one (label, thread) pair — applying a label is idempotent.
CREATE UNIQUE INDEX "label_threads_label_thread_key" ON "label_threads"("labelId", "threadId");
-- "the threads tagged with this label" (the label view + the per-row label chips).
CREATE INDEX "label_threads_label_idx" ON "label_threads"("labelId");
