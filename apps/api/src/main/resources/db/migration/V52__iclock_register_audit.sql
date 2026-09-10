-- V52: the algorithm version a biometric belongs to, and the record of auditing a terminal.
--
-- WHAT THE FIRST QUERY-USERINFO CANARY REVEALED, 2026-09-10. Asking a terminal for its user table
-- does NOT return a user table on this firmware. It returns the whole biometric inventory instead:
--
--   BIODATA  Pin=1051  No=0 Index=0 Valid=1 Duress=0 Type=9 MajorVer=36 MinorVer=1 Tmp=...
--   BIOPHOTO PIN=22383 FileName=22383.jpg Type=9 Size=67004 Content=<jpeg>
--   FP       PIN=17400 FID=6 Size=1544 Valid=1 TMP=...
--
-- Two facts in that shape changed the design.
--
-- FACE IS Type=9, not the Type=2 the specification documents. The code was written to the spec and
-- the spec is wrong for this hardware.
--
-- AND THE FACE ALGORITHM VERSION DIFFERS BY PLATFORM: the NES cafeteria terminal reports MajorVer=36
-- MinorVer=1, the ZHM gate reports 39.3 (with a handful of Type=8 / 12.0 records on one ZHM pin, a
-- third modality kept here in the ledger rather than acted on). A 36.1 face template pushed to a
-- 39.3 terminal is not a risk to be monitored; it is a different format. So the version travels with
-- every template and with every device, and the command funnel refuses the mismatch outright rather
-- than sending it and reading the failure afterwards.

ALTER TABLE "iclock_biometric_templates" ADD COLUMN "algoMajor" INTEGER;
ALTER TABLE "iclock_biometric_templates" ADD COLUMN "algoMinor" INTEGER;
-- Nullable because fingerprint records carry no version at all on this firmware, and because the
-- five templates captured before this migration have none. Absent is a real state, distinct from
-- zero, and the funnel treats it as "not proven compatible".

-- What each terminal last told us about its own face algorithm. Learned from its BIODATA push;
-- null until that terminal has been audited at least once.
ALTER TABLE "iclock_devices" ADD COLUMN "faceAlgoMajor" INTEGER;
ALTER TABLE "iclock_devices" ADD COLUMN "faceAlgoMinor" INTEGER;

-- When a terminal's register was last compared against the roster, and what that comparison found.
-- One row per audit, kept as history: an audit is evidence about a moment, and the useful question
-- later is usually "was this already true last week".
CREATE TABLE "iclock_register_audits" (
    "id" TEXT NOT NULL,
    "deviceId" TEXT NOT NULL,

    -- Counts of the four evidence-backed groups. NAME DRIFT is deliberately absent: this firmware
    -- never sends a name, so a device-vs-roster name comparison has no input on this fleet. Pushing
    -- a corrected name stays available per person; it simply cannot be an audit FINDING, because
    -- there is nothing to find it from.
    "pinsOnDevice" INTEGER NOT NULL DEFAULT 0,
    "clean" INTEGER NOT NULL DEFAULT 0,
    "stale" INTEGER NOT NULL DEFAULT 0,
    "unknown" INTEGER NOT NULL DEFAULT 0,
    "templateGaps" INTEGER NOT NULL DEFAULT 0,

    -- REQUESTED -> RECEIVED. A terminal answers a query over many pushes across minutes, so an audit
    -- is only readable once the dump has stopped arriving.
    "status" TEXT NOT NULL DEFAULT 'REQUESTED',
    "commandId" TEXT,
    "requestedBy" TEXT NOT NULL,
    "requestedAt" TIMESTAMPTZ NOT NULL DEFAULT now(),
    "completedAt" TIMESTAMPTZ,

    CONSTRAINT "iclock_register_audits_pkey" PRIMARY KEY ("id")
);

ALTER TABLE "iclock_register_audits" ADD CONSTRAINT "iclock_register_audits_status_known"
    CHECK ("status" IN ('REQUESTED', 'RECEIVED', 'FAILED'));

ALTER TABLE "iclock_register_audits" ADD CONSTRAINT "iclock_register_audits_deviceId_fkey"
    FOREIGN KEY ("deviceId") REFERENCES "iclock_devices"("id") ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE "iclock_register_audits" ADD CONSTRAINT "iclock_register_audits_commandId_fkey"
    FOREIGN KEY ("commandId") REFERENCES "iclock_device_commands"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- "Last audited" on the devices list.
CREATE INDEX "iclock_register_audits_device_requested_idx"
    ON "iclock_register_audits"("deviceId", "requestedAt" DESC);

-- What a terminal reported holding, per pin, at the moment of an audit. This is what the review
-- screen groups and what the delete selection is built from.
CREATE TABLE "iclock_register_entries" (
    "id" TEXT NOT NULL,
    "auditId" TEXT NOT NULL,
    "pin" TEXT NOT NULL,

    -- Inventory, straight off the wire: how many fingers, whether a face template is present, and
    -- whether the terminal also holds an enrolment photo.
    "fingerCount" INTEGER NOT NULL DEFAULT 0,
    "fingerIndexes" TEXT,
    "hasFace" BOOLEAN NOT NULL DEFAULT false,
    "hasPhoto" BOOLEAN NOT NULL DEFAULT false,

    -- CLEAN    active roster row at this building
    -- STALE    roster row here, but deactivated
    -- UNKNOWN  never rostered at this building - register debris, or somebody nobody has added yet
    "verdict" TEXT NOT NULL,
    "personId" TEXT,
    "personName" TEXT,

    CONSTRAINT "iclock_register_entries_pkey" PRIMARY KEY ("id")
);

ALTER TABLE "iclock_register_entries" ADD CONSTRAINT "iclock_register_entries_verdict_known"
    CHECK ("verdict" IN ('CLEAN', 'STALE', 'UNKNOWN'));

ALTER TABLE "iclock_register_entries" ADD CONSTRAINT "iclock_register_entries_audit_pin_key"
    UNIQUE ("auditId", "pin");

ALTER TABLE "iclock_register_entries" ADD CONSTRAINT "iclock_register_entries_auditId_fkey"
    FOREIGN KEY ("auditId") REFERENCES "iclock_register_audits"("id") ON DELETE CASCADE ON UPDATE CASCADE;

CREATE INDEX "iclock_register_entries_audit_verdict_idx"
    ON "iclock_register_entries"("auditId", "verdict");
