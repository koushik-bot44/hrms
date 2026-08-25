-- V42: iClock P0.1 — content dedupe for punches, plus per-device registry stamps. Additive.
--
-- Context from the first real LAN capture (firmware ZAM180-NF50VD-4.0.12-CR-1545-01, pushver 2.4.1):
-- the terminal calls /iclock/*.aspx, which P0 only caught with its catch-all, so 16,970 ATTLOG lines were
-- captured raw but never parsed. P0.1 maps the .aspx forms and replays those captured bodies. Replay and
-- live ingest MUST therefore share one dedupe mechanism, or the replay would double-count every line that
-- also arrives live.

-- The shared dedupe key: md5 of the serial plus the verbatim line. Content-addressed on purpose — the
-- device sends a constant Stamp=9999 and no per-record id, so the line itself is the only stable identity
-- available. Both the replay and the live insert path compute this identically and rely on the unique
-- index below, so "insert if new" is decided by the DATABASE, not by application-side checking (which
-- would race between a replay and a concurrent live push).
--
-- KNOWN TRADE-OFF, deliberate: a genuinely repeated punch — same PIN, same second, same status — is
-- byte-identical to a duplicate upload and will be collapsed. On this firmware the timestamp has
-- one-second resolution, so two distinct events from one PIN inside the same second are
-- indistinguishable. Losing that is preferable to double-counting a 17k-line backlog on every re-upload.
ALTER TABLE "iclock_raw_punches" ADD COLUMN "dedupeKey" TEXT;

-- Backfill. md5() is built in, so no pgcrypto extension is needed.
UPDATE "iclock_raw_punches"
   SET "dedupeKey" = md5("serialNumber" || E'\n' || "rawLine")
 WHERE "dedupeKey" IS NULL;

-- Collapse any pre-existing duplicates before the unique index is created, keeping the earliest row.
-- Nothing should match in practice, but the index creation must not be able to fail on legacy data.
DELETE FROM "iclock_raw_punches" a
 USING "iclock_raw_punches" b
 WHERE a."dedupeKey" = b."dedupeKey"
   AND (a."receivedAt", a."id") > (b."receivedAt", b."id");

ALTER TABLE "iclock_raw_punches" ALTER COLUMN "dedupeKey" SET NOT NULL;

-- The dedupe contract. The key already contains the serial, so a single-column index is sufficient.
CREATE UNIQUE INDEX "iclock_raw_punches_dedupeKey_key" ON "iclock_raw_punches"("dedupeKey");

-- Per-device registry cursors returned in the handshake options block.
-- The observed firmware sends a FIXED Stamp=9999 on every ATTLOG post and OpStamp=9999 on OPERLOG/BIODATA,
-- so these are not reliable high-water marks on this model; they are persisted and echoed back so the
-- device sees a consistent server view, while the "OK: <n>" acknowledgement plus the dedupe index above
-- are what actually prevent duplicate history from being stored.
ALTER TABLE "iclock_devices" ADD COLUMN "attlogStamp" TEXT;
ALTER TABLE "iclock_devices" ADD COLUMN "opStamp" TEXT;
