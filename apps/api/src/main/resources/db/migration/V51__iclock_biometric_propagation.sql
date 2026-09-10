-- V51: fingerprint templates, and the state that lets one enrolment reach a whole building.
--
-- THE DISCOVERY THAT SHAPES THIS TABLE. The plan of record was to fetch templates over the command
-- channel with DATA QUERY FINGERTMP, and to expect them back as table=BIODATA. Neither is what this
-- fleet does. Across 131,000 logged requests it has POSTed exactly three tables — ATTLOG, OPERLOG
-- and the command acks — and BIODATA has never appeared once. Templates arrive UNASKED, inside
-- OPERLOG, in the same push as the operation record:
--
--   FP PIN=8003<TAB>FID=6<TAB>Size=1544<TAB>Valid=1<TAB>TMP=TcdTUzIxAAAEhIsECAUHCc7QAAAo...
--
-- Five have landed since adoption, the most recent two on 2026-09-09 at 22:27 and 22:29 IST. So the
-- capture half of propagation needs no command at all: enrol on any terminal and the template comes
-- to us. This table is where it lands, and the rest of the feature is the outbound half.
--
-- ONE ROW PER (site, pin, finger). A person may hold several fingers, and a re-enrolment REPLACES
-- the template for that finger rather than accumulating — the device does the same thing, and a
-- history of superseded templates would be a pile of biometric data kept for no purpose.

CREATE TABLE "iclock_biometric_templates" (
    "id" TEXT NOT NULL,

    -- Scoped to a building, not to a person: a template arrives from a terminal, and the pin is what
    -- the terminal knows. The roster row is looked up and attached when there is one, but a template
    -- from an unmapped pin is still worth keeping — it is the enrolment somebody just performed.
    "siteId" TEXT NOT NULL,
    "pin" TEXT NOT NULL,
    "personId" TEXT,

    -- Which finger. The device's own index, 0-9. Every template observed on this fleet so far is
    -- FID=6, which is the on-device menu's default position, not a coincidence to rely on.
    -- A face carries no finger index and is stored at 0.
    "fid" INTEGER NOT NULL,

    -- WHICH BIOMETRIC. 1 = fingerprint, 2 = face, following the device's own Type numbering.
    -- Part of the identity below, not a detail: a face and a finger for the same person would
    -- otherwise collide at fid 0 and silently overwrite each other. Only fingerprints have ever
    -- arrived here; the column exists so that the first face has somewhere to land.
    "bioType" INTEGER NOT NULL DEFAULT 1,

    -- The template exactly as the device serialised it, base64, plus the two fields that travel with
    -- it. Size is the DECODED byte count the device reported; it is stored rather than recomputed
    -- because it is what has to be sent back, and a disagreement between the two is a fact worth
    -- being able to see.
    "template" TEXT NOT NULL,
    "size" INTEGER NOT NULL,
    "valid" INTEGER NOT NULL DEFAULT 1,

    -- Cheap identity for "is this the same template we already propagated". Propagation is idempotent
    -- on this, which matters because a terminal that re-sends its OPERLOG history would otherwise
    -- re-push every template in the building.
    "fingerprint" TEXT NOT NULL,

    -- Where it was captured. Kept because template portability ACROSS FIRMWARE FAMILIES is unproven
    -- here: every template observed so far came from a ZAM180 gate, and no NES cafeteria terminal has
    -- ever sent one. If a propagation to the other family fails, this column is how that gets read.
    "sourceDeviceId" TEXT,

    "capturedAt" TIMESTAMPTZ NOT NULL DEFAULT now(),
    "createdAt" TIMESTAMPTZ NOT NULL DEFAULT now(),
    "updatedAt" TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT "iclock_biometric_templates_pkey" PRIMARY KEY ("id")
);

ALTER TABLE "iclock_biometric_templates" ADD CONSTRAINT "iclock_biometric_templates_site_pin_fid_key"
    UNIQUE ("siteId", "pin", "bioType", "fid");

ALTER TABLE "iclock_biometric_templates" ADD CONSTRAINT "iclock_biometric_templates_siteId_fkey"
    FOREIGN KEY ("siteId") REFERENCES "iclock_sites"("id") ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE "iclock_biometric_templates" ADD CONSTRAINT "iclock_biometric_templates_personId_fkey"
    FOREIGN KEY ("personId") REFERENCES "iclock_people"("id") ON DELETE SET NULL ON UPDATE CASCADE;

ALTER TABLE "iclock_biometric_templates" ADD CONSTRAINT "iclock_biometric_templates_sourceDeviceId_fkey"
    FOREIGN KEY ("sourceDeviceId") REFERENCES "iclock_devices"("id") ON DELETE SET NULL ON UPDATE CASCADE;
-- SET NULL rather than CASCADE: the template outlives the terminal it was captured on. Losing the
-- provenance is acceptable; losing the fingerprint because a device was unclaimed is not.

CREATE INDEX "iclock_biometric_templates_person_idx"
    ON "iclock_biometric_templates"("personId");


-- WHO IS ENROLLED WHERE. The console's "enrolled on 4 of 4" and the repair action both read this.
--
-- Derived state, deliberately materialised. It could be reconstructed by walking the command log,
-- but that log is a record of what was ATTEMPTED, and the question an operator is asking — can this
-- person get through that door — is about what SUCCEEDED. Those differ exactly when something went
-- wrong, which is the only time anybody looks.

CREATE TABLE "iclock_device_enrolments" (
    "id" TEXT NOT NULL,
    "deviceId" TEXT NOT NULL,
    "pin" TEXT NOT NULL,
    "fid" INTEGER NOT NULL,
    "bioType" INTEGER NOT NULL DEFAULT 1,

    -- Which template this device is believed to hold, so a later re-enrolment can tell "has the new
    -- one" from "still has the old one" without comparing blobs.
    "fingerprint" TEXT,

    -- SOURCE  — captured here; the device already holds it and nothing was sent.
    -- PENDING — a propagation command is queued or in flight.
    -- PRESENT — the device acknowledged the template.
    -- FAILED  — it refused it. On this fleet the interesting case is a cross-family refusal.
    "status" TEXT NOT NULL DEFAULT 'PENDING',

    "commandId" TEXT,
    "failureReason" TEXT,
    "createdAt" TIMESTAMPTZ NOT NULL DEFAULT now(),
    "updatedAt" TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT "iclock_device_enrolments_pkey" PRIMARY KEY ("id")
);

ALTER TABLE "iclock_device_enrolments" ADD CONSTRAINT "iclock_device_enrolments_device_pin_fid_key"
    UNIQUE ("deviceId", "pin", "bioType", "fid");

ALTER TABLE "iclock_device_enrolments" ADD CONSTRAINT "iclock_device_enrolments_status_known"
    CHECK ("status" IN ('SOURCE', 'PENDING', 'PRESENT', 'FAILED'));

ALTER TABLE "iclock_device_enrolments" ADD CONSTRAINT "iclock_device_enrolments_deviceId_fkey"
    FOREIGN KEY ("deviceId") REFERENCES "iclock_devices"("id") ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE "iclock_device_enrolments" ADD CONSTRAINT "iclock_device_enrolments_commandId_fkey"
    FOREIGN KEY ("commandId") REFERENCES "iclock_device_commands"("id") ON DELETE SET NULL ON UPDATE CASCADE;

CREATE INDEX "iclock_device_enrolments_pin_idx"
    ON "iclock_device_enrolments"("pin");


-- THE COMMAND KINDS P3 ADDS. V50 said a widening should be one additive statement; this is it.
--
-- ENROLL_FP        trigger fingerprint capture on one terminal
-- ENROLL_BIO       trigger face capture on one terminal (UNPROVEN on this firmware)
-- QUERY_USERINFO   ask a terminal for its user table (read-only on the device)
-- UPDATE_FINGERTMP push a captured fingerprint to the other terminals in the building
-- UPDATE_BIODATA   the same for a face template (UNPROVEN)
ALTER TABLE "iclock_device_commands" DROP CONSTRAINT "iclock_device_commands_kind_known";
ALTER TABLE "iclock_device_commands" ADD CONSTRAINT "iclock_device_commands_kind_known"
    CHECK ("kind" IN ('UPDATE_USERINFO', 'SET_TIME', 'DELETE_USER',
                      'ENROLL_FP', 'ENROLL_BIO', 'QUERY_USERINFO',
                      'UPDATE_FINGERTMP', 'UPDATE_BIODATA'));
