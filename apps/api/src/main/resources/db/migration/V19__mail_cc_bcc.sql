-- V19: internal mail CC / BCC (§8). Additive.
--   Each message_recipients row gains a recipientType (TO/CC/BCC). Existing rows are single TO recipients,
--   so the column defaults to 'TO' — that backfills every pre-existing recipient correctly. BCC recipients
--   are delivered the message but hidden from other recipients (enforced in the service, per viewer).

CREATE TYPE "RecipientType" AS ENUM ('TO', 'CC', 'BCC');

ALTER TABLE "message_recipients"
    ADD COLUMN "recipientType" "RecipientType" NOT NULL DEFAULT 'TO';
