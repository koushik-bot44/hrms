-- V50: the command queue — the FIRST server -> device write path in this system.
--
-- Everything before this only ever read from the terminals. A command changes what is on a device's
-- screen and in its user table, and a bad one is not a wrong number in a report: it is 250 people
-- renamed on the hardware. The table is shaped so that every send is attributable, bounded, and
-- stops on its own.
--
-- NOTHING HERE AUTO-QUEUES. Editing a person does not enqueue a command; an operator has to ask.
-- That is enforced in the service, but it is the reason this table has createdBy NOT NULL: a row
-- with no author would mean the system had decided to write to hardware by itself.

CREATE TABLE "iclock_device_commands" (
    "id" TEXT NOT NULL,
    "deviceId" TEXT NOT NULL,

    -- What kind, so the console can group and the ack handler can reason without parsing payload.
    -- Deliberately a CHECK rather than an enum type: P3 adds kinds and a widening should be one
    -- additive statement.
    "kind" TEXT NOT NULL,

    -- The exact bytes served to the device, MINUS the "C:<id>:" prefix, which is applied at serve
    -- time from this row's own id. Storing the body verbatim is what makes the log worth having:
    -- when a terminal does something surprising, the question is always "what precisely did we send".
    "payload" TEXT NOT NULL,

    -- PENDING -> SENT -> ACKED | FAILED. Never back.
    "status" TEXT NOT NULL DEFAULT 'PENDING',

    -- How many times this command has been handed to the device. A terminal that takes a command and
    -- never acks would otherwise be re-served it on every poll, forever, at ~2 polls/second across
    -- the fleet. Past the cap it becomes FAILED and is surfaced instead of retried.
    "serveCount" INTEGER NOT NULL DEFAULT 0,

    -- What the device said back, verbatim, whatever shape it turns out to be. No device has ever
    -- called devicecmd on this fleet (0 rows in iclock_request_logs), so the ack format is UNPROVEN
    -- and this column exists to capture reality rather than assume it.
    "ackRaw" TEXT,
    "ackReturn" TEXT,

    -- Why a command failed, in a sentence an operator can act on.
    "failureReason" TEXT,

    -- Correlation, so a command can be explained without a log dive.
    "personId" TEXT,
    "devicePin" TEXT,

    "createdBy" TEXT NOT NULL,
    "createdAt" TIMESTAMPTZ NOT NULL DEFAULT now(),
    "sentAt" TIMESTAMPTZ,
    "completedAt" TIMESTAMPTZ,

    CONSTRAINT "iclock_device_commands_pkey" PRIMARY KEY ("id")
);

ALTER TABLE "iclock_device_commands" ADD CONSTRAINT "iclock_device_commands_status_known"
    CHECK ("status" IN ('PENDING', 'SENT', 'ACKED', 'FAILED'));

ALTER TABLE "iclock_device_commands" ADD CONSTRAINT "iclock_device_commands_kind_known"
    CHECK ("kind" IN ('UPDATE_USERINFO', 'SET_TIME', 'DELETE_USER'));

-- Bean validation mirrors both CHECKs, per the standing rule: ddl-auto:validate never checks CHECK
-- constraints, so a constraint alone surfaces as a 500 rather than a readable 400.

ALTER TABLE "iclock_device_commands" ADD CONSTRAINT "iclock_device_commands_deviceId_fkey"
    FOREIGN KEY ("deviceId") REFERENCES "iclock_devices"("id") ON DELETE CASCADE ON UPDATE CASCADE;
-- CASCADE: a queued command for a terminal that no longer exists is meaningless, not a record of
-- fact. It should follow the device out rather than block its removal.

ALTER TABLE "iclock_device_commands" ADD CONSTRAINT "iclock_device_commands_personId_fkey"
    FOREIGN KEY ("personId") REFERENCES "iclock_people"("id") ON DELETE SET NULL ON UPDATE CASCADE;
-- SET NULL, unlike the device: the command still happened and the log should keep saying so even if
-- the roster row is later removed. The pin is retained alongside, so the row stays readable.

-- THE SERVE QUERY: oldest PENDING for one device. Partial, because PENDING is a small fraction of
-- the table's lifetime rows and this runs on every poll — ~118,000 polls so far on this fleet.
CREATE INDEX "iclock_device_commands_pending_idx"
    ON "iclock_device_commands"("deviceId", "createdAt")
    WHERE "status" = 'PENDING';

-- The console's command log, newest-first per device.
CREATE INDEX "iclock_device_commands_device_created_idx"
    ON "iclock_device_commands"("deviceId", "createdAt" DESC);

-- Ack lookup is by primary key, so no further index is needed.
