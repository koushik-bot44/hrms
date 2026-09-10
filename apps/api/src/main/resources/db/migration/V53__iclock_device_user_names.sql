-- V53: what each terminal calls each person, so NAME DRIFT can be a finding again.
--
-- THE CORRECTION THIS EXISTS FOR. V52 asserts, in a comment, that this firmware never sends names
-- and that a device-versus-roster name comparison therefore has no input. That is false, and the
-- evidence was in hand when it was written: a single DATA QUERY USERINFO on ZHM2252000230 returned
-- 141 records of the form
--
--   USER PIN=100045<TAB>Name=Sai Koushik<TAB>Pri=0<TAB>Passwd=<TAB>Card=<TAB>Grp=1<TAB>TZ=...
--
-- The claim came from an evidence query that used LIMIT 5 while BIOPHOTO rows matched its "Name="
-- pattern through "FileName=" — the USER rows existed and were simply never displayed. An evidence
-- query gets no LIMIT, or one LIMIT per record type; a sample is not a census.
--
-- ONE ROW PER (terminal, pin), holding the terminal's own label. Not merged into iclock_people:
-- the roster name is what a human entered and the device name is what the hardware believes, and
-- the entire value of NAME DRIFT is in being able to see the two disagree.

CREATE TABLE "iclock_device_user_names" (
    "id" TEXT NOT NULL,
    "deviceId" TEXT NOT NULL,
    "pin" TEXT NOT NULL,

    -- Exactly as the terminal spelled it, including the register junk and slug names that P3 exists
    -- to replace. Never cleaned on the way in: a name that differs only by trimming is a different
    -- kind of finding from a name that is somebody else entirely, and normalising here would hide
    -- the first.
    "deviceName" TEXT,

    -- The privilege the terminal reports. Read, recorded, never echoed — this system cannot grant a
    -- privilege and does not track who is a device admin.
    "privilege" INTEGER,

    "seenAt" TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT "iclock_device_user_names_pkey" PRIMARY KEY ("id")
);

ALTER TABLE "iclock_device_user_names" ADD CONSTRAINT "iclock_device_user_names_device_pin_key"
    UNIQUE ("deviceId", "pin");

ALTER TABLE "iclock_device_user_names" ADD CONSTRAINT "iclock_device_user_names_deviceId_fkey"
    FOREIGN KEY ("deviceId") REFERENCES "iclock_devices"("id") ON DELETE CASCADE ON UPDATE CASCADE;

CREATE INDEX "iclock_device_user_names_pin_idx" ON "iclock_device_user_names"("pin");
