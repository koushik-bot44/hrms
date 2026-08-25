-- V41: eSSL/ZKTeco ADMS ("iClock" push protocol) ingestion — Phase 0. Additive; nothing existing is touched.
--
-- A biometric terminal on the customer LAN pushes attendance over plain HTTP to /iclock/*. P0 stores what the
-- device sends VERBATIM and derives nothing: there is no PIN -> employee mapping and no attendance_sessions
-- interaction yet (that is Phase 1). The point of P0 is to learn this firmware's dialect from real traffic.
--
-- NOTE ON THE FEATURE FLAG: app.iclock.enabled gates the *writes* to these tables, but Flyway is unconditional
-- (application.yml flyway.enabled: true) and @Entity classes are scanned by the persistence unit, not the bean
-- factory. So these three tables are created and schema-validated on the very next deploy whether or not the
-- flag is set. The DDL below must be correct on its FIRST production boot — a column/entity type mismatch
-- aborts context startup app-wide, not just this module.
--
-- Timestamp style: TIMESTAMPTZ (matching the attendance tables V16/V18/V38 this data will eventually feed),
-- NOT the timestamp(3) used by V30-V39. Every Instant field on the entities therefore carries
-- @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE); Hibernate 6 would otherwise map a bare Instant to
-- TIMESTAMP_UTC. The annotation and the DDL type MUST agree or the app refuses to start.
--
-- The device's own punch time is deliberately kept as TEXT ("punchedAtRaw"), NOT parsed into a timestamp: the
-- device sends an offset-less local wall-clock string from a drifting RTC, and choosing a timezone for it is a
-- policy decision Phase 1 must make deliberately. Storing it raw is lossless.

-- One row per physical terminal, keyed by the serial number it sends as ?SN=. Rows are created on first
-- contact; a device is RECORDED here, never TRUSTED (there is no authentication in this protocol).
CREATE TABLE "iclock_devices" (
    "id" TEXT NOT NULL,
    "serialNumber" TEXT NOT NULL,
    "name" TEXT,
    -- Denormalized, NULLABLE tenant key with NO foreign key — the same shape as "audit_logs"."companyId"
    -- (V1__baseline.sql:119). Nothing populates it in P0: a device announces only its serial, so it cannot be
    -- attributed to a company until Phase 1 adoption. (Contrast "attendance_sessions"."companyId", which is
    -- NOT NULL — that precedent does not apply here.)
    "companyId" TEXT,
    "lastSeenAt" TIMESTAMPTZ,
    "lastHandshakeAt" TIMESTAMPTZ,
    -- UNCLAIMED until a Phase-1 operator adopts the device into a company.
    "status" TEXT NOT NULL DEFAULT 'UNCLAIMED',
    -- Verbatim options/info string the firmware volunteers on handshake, for dialect forensics.
    "firmwareInfo" TEXT,
    "createdAt" TIMESTAMPTZ NOT NULL DEFAULT now(),
    "updatedAt" TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT "iclock_devices_pkey" PRIMARY KEY ("id")
);

-- The serial is the device's only identity; the upsert races on it when a device reconnects concurrently.
CREATE UNIQUE INDEX "iclock_devices_serialNumber_key" ON "iclock_devices"("serialNumber");

-- One row per ATTLOG line pushed by a device. "rawLine" is the authoritative record; the parsed columns are
-- nullable convenience projections that are simply left NULL when a line is short or malformed, so an
-- unexpected firmware column layout can never cost us the data.
CREATE TABLE "iclock_raw_punches" (
    "id" TEXT NOT NULL,
    -- Best-effort link only, and ON DELETE SET NULL: if the device upsert loses a race or fails outright we
    -- still persist the punches with a NULL deviceId rather than dropping them. "serialNumber" below is the
    -- durable identity.
    "deviceId" TEXT,
    "serialNumber" TEXT NOT NULL,
    -- The device's enrolment number. Opaque in P0 — deliberately NO foreign key to "employees": mapping a PIN
    -- to an employee is Phase 1, and a punch from an unknown/unenrolled PIN must still be stored.
    "devicePin" TEXT,
    -- Device local wall-clock, exactly as sent (typically "YYYY-MM-DD HH:MM:SS", no offset). See header note.
    "punchedAtRaw" TEXT,
    "statusCode" TEXT,
    "verifyMode" TEXT,
    "workCode" TEXT,
    "rawLine" TEXT NOT NULL DEFAULT '',
    "lineNumber" INTEGER NOT NULL DEFAULT 0,
    -- Correlates back to the request that carried this line. NO foreign key on purpose: the request-log row is
    -- written in its own committed transaction and could in principle fail independently, and an orphaned
    -- correlation id is far cheaper than losing a punch.
    "requestLogId" TEXT,
    "receivedAt" TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT "iclock_raw_punches_pkey" PRIMARY KEY ("id")
);

ALTER TABLE "iclock_raw_punches" ADD CONSTRAINT "iclock_raw_punches_deviceId_fkey"
    FOREIGN KEY ("deviceId") REFERENCES "iclock_devices"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- Per-device punch history, newest first — the P0 verification query.
CREATE INDEX "iclock_raw_punches_serial_time_idx"
    ON "iclock_raw_punches"("serialNumber", "receivedAt" DESC);

-- Deliberately NO unique index on (serialNumber, devicePin, punchedAtRaw): a terminal re-uploads its whole
-- batch after any non-"OK" reply, and silently collapsing those duplicates would hide exactly the retry storm
-- this module exists to detect. Duplicates are evidence in P0.

-- Verbatim capture of EVERY /iclock/** request — the diagnostic record we read to learn the firmware dialect.
-- This is a firehose (a device polls getrequest every few seconds) and P0 ships NO retention policy; see the
-- app.iclock.enabled flag and the runbook note. Prune manually until Phase 1.
CREATE TABLE "iclock_request_logs" (
    "id" TEXT NOT NULL,
    "serialNumber" TEXT,
    "method" TEXT NOT NULL,
    "path" TEXT NOT NULL,
    "queryString" TEXT,
    -- Header names + values, one per line. Sensitive headers (Authorization, Cookie, ...) are REDACTED before
    -- the row is written — this filter runs ahead of Spring Security, so it sees requests security would have
    -- rejected, including any that carry a live bearer token.
    "headers" TEXT,
    "body" TEXT,
    "bodyBytes" INTEGER NOT NULL DEFAULT 0,
    -- True when the body exceeded app.iclock.max-body-bytes and was cut short.
    "bodyTruncated" BOOLEAN NOT NULL DEFAULT false,
    -- True when NUL bytes were stripped to make the payload storable as Postgres TEXT (a binary table such as
    -- FDATA would trip this). Without the flag, a sanitized row would be indistinguishable from a clean one.
    "sanitized" BOOLEAN NOT NULL DEFAULT false,
    "remoteAddr" TEXT,
    -- The ?table= query parameter (ATTLOG / OPERLOG / ...), lifted out for easy grouping. "table" is reserved.
    "tableName" TEXT,
    "responseStatus" INTEGER,
    "durationMs" INTEGER,
    "receivedAt" TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT "iclock_request_logs_pkey" PRIMARY KEY ("id")
);

-- Two indexes only, on a write-heavy table: a global newest-first scan, and the same per device.
CREATE INDEX "iclock_request_logs_time_idx" ON "iclock_request_logs"("receivedAt" DESC);
CREATE INDEX "iclock_request_logs_serial_time_idx"
    ON "iclock_request_logs"("serialNumber", "receivedAt" DESC);
