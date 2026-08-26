-- V43: iClock P1a — site topology, device claiming, PIN mapping, effective punches. Additive.
--
-- P0/P0.1 made the raw event log complete and trustworthy. P1a makes it ATTRIBUTABLE: a punch becomes
-- "employee X, at site S, entering" instead of "pin 18292 on serial ZHM2252000230".
--
-- TIMESTAMP STYLE: TIMESTAMPTZ throughout, matching the attendance tables (V16/V18/V38) and the iclock
-- tables (V41/V42) this joins to — NOT the timestamp(3) used by the onboarding/mail tables. Every
-- Instant field on the entities therefore needs @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE);
-- Hibernate 6 maps a bare Instant to TIMESTAMP_UTC and ddl-auto=validate aborts startup APP-WIDE on a
-- mismatch. The one DATE column ("shiftDate") maps to java.time.LocalDate with no @JdbcTypeCode.
--
-- BOOT-ABORT NOTE: Flyway is unconditional and @Entity classes are scanned by the persistence unit, not
-- the bean factory, so these tables are created and schema-validated on the next deploy regardless of
-- app.iclock.enabled. This DDL must be correct on its FIRST production boot.

-- ---------------------------------------------------------------------------------------------------
-- Sites: the physical premises a set of terminals guards. A site spans one or more companies.
-- ---------------------------------------------------------------------------------------------------
CREATE TABLE "iclock_sites" (
    "id" TEXT NOT NULL,
    "name" TEXT NOT NULL,
    -- IANA zone used to interpret the device's offset-less wall-clock stamp. Defaulted rather than
    -- required because every terminal on this fleet is in one zone today; the column exists so the
    -- first out-of-zone site is a data change, not a migration.
    "timezone" TEXT NOT NULL DEFAULT 'Asia/Kolkata',
    "createdAt" TIMESTAMPTZ NOT NULL DEFAULT now(),
    "updatedAt" TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT "iclock_sites_pkey" PRIMARY KEY ("id")
);

-- Case-insensitive name uniqueness, following the companies.slug precedent (V28).
CREATE UNIQUE INDEX "iclock_sites_name_key" ON "iclock_sites"(lower("name"));

-- ---------------------------------------------------------------------------------------------------
-- Site <-> company membership.
--
-- DECISION D2: a company belongs to EXACTLY ONE site. That is enforced here by UNIQUE("companyId"),
-- and it is what makes the pin rule expressible as a real database constraint rather than a runtime
-- report. With a company in at most one site, an employee has exactly one site, so a pin's uniqueness
-- scope collapses from a set-valued two-hop join to a single scalar that can be denormalised onto
-- iclock_employee_pins and covered by UNIQUE("siteId","pin"). Relaxing this to many-to-many later
-- would make that constraint unsatisfiable and is a schema change, not a config change.
-- ---------------------------------------------------------------------------------------------------
CREATE TABLE "iclock_site_companies" (
    "id" TEXT NOT NULL,
    "siteId" TEXT NOT NULL,
    "companyId" TEXT NOT NULL,
    "createdAt" TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT "iclock_site_companies_pkey" PRIMARY KEY ("id")
);

-- D2, the load-bearing constraint: one site per company.
CREATE UNIQUE INDEX "iclock_site_companies_companyId_key" ON "iclock_site_companies"("companyId");
CREATE INDEX "iclock_site_companies_siteId_idx" ON "iclock_site_companies"("siteId");

ALTER TABLE "iclock_site_companies" ADD CONSTRAINT "iclock_site_companies_siteId_fkey"
    FOREIGN KEY ("siteId") REFERENCES "iclock_sites"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
-- RESTRICT, not CASCADE: unlinking a company is an administrative act with pin consequences, so a
-- company purge must fail loudly rather than silently orphaning that company's pins.
ALTER TABLE "iclock_site_companies" ADD CONSTRAINT "iclock_site_companies_companyId_fkey"
    FOREIGN KEY ("companyId") REFERENCES "companies"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- ---------------------------------------------------------------------------------------------------
-- Device claiming. A device self-announces on first contact (P0) and is then ADOPTED by an operator.
-- ---------------------------------------------------------------------------------------------------
ALTER TABLE "iclock_devices" ADD COLUMN "siteId" TEXT;
-- Physical role. Direction comes ONLY from here: on this fleet statusCode is always 255 and verifyMode
-- always 15, so the punch payload carries no direction signal whatsoever.
ALTER TABLE "iclock_devices" ADD COLUMN "area" TEXT;
ALTER TABLE "iclock_devices" ADD COLUMN "direction" TEXT;
ALTER TABLE "iclock_devices" ADD COLUMN "claimedAt" TIMESTAMPTZ;

ALTER TABLE "iclock_devices" ADD CONSTRAINT "iclock_devices_siteId_fkey"
    FOREIGN KEY ("siteId") REFERENCES "iclock_sites"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- Nullable-tolerant CHECKs: an UNCLAIMED device has all four NULL, which must stay legal for the two
-- live rows already in production.
ALTER TABLE "iclock_devices" ADD CONSTRAINT "iclock_devices_area_valid"
    CHECK ("area" IS NULL OR "area" IN ('GATE', 'CAFETERIA'));
ALTER TABLE "iclock_devices" ADD CONSTRAINT "iclock_devices_direction_valid"
    CHECK ("direction" IS NULL OR "direction" IN ('IN', 'OUT', 'MIXED'));
ALTER TABLE "iclock_devices" ADD CONSTRAINT "iclock_devices_status_valid"
    CHECK ("status" IN ('UNCLAIMED', 'CLAIMED'));
-- A claim is all-or-nothing: a half-claimed device would promote punches with a NULL direction.
ALTER TABLE "iclock_devices" ADD CONSTRAINT "iclock_devices_claim_complete"
    CHECK (
      ("status" = 'UNCLAIMED' AND "siteId" IS NULL AND "area" IS NULL
        AND "direction" IS NULL AND "claimedAt" IS NULL)
      OR
      ("status" = 'CLAIMED' AND "siteId" IS NOT NULL AND "area" IS NOT NULL
        AND "direction" IS NOT NULL AND "claimedAt" IS NOT NULL)
    );

-- Serves the adoption queue (?status=UNCLAIMED) and the per-site fleet roster.
CREATE INDEX "iclock_devices_status_idx" ON "iclock_devices"("status");
CREATE INDEX "iclock_devices_siteId_idx" ON "iclock_devices"("siteId");

-- ---------------------------------------------------------------------------------------------------
-- PIN mapping: device enrolment number -> employee.
--
-- DECISION D3: pins are stored CANONICAL — leading zeros stripped — and ingest canonicalises the same
-- way before resolving. This is not hypothetical: the live fleet sends 000261, 000262 and 02919 padded,
-- so an operator importing "261" would otherwise never match a punch and the failure would look
-- identical to an unenrolled finger. iclock_raw_punches."rawLine" keeps the original verbatim.
--
-- "siteId" is DENORMALISED from employee -> company -> iclock_site_companies at write time. It is what
-- makes UNIQUE("siteId","pin") a real constraint. It MUST be recomputed, in the same transaction, by
-- any operation that moves a company between sites.
-- ---------------------------------------------------------------------------------------------------
CREATE TABLE "iclock_employee_pins" (
    "id" TEXT NOT NULL,
    "employeeId" TEXT NOT NULL,
    "pin" TEXT NOT NULL,
    "siteId" TEXT NOT NULL,
    "createdAt" TIMESTAMPTZ NOT NULL DEFAULT now(),
    "updatedAt" TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT "iclock_employee_pins_pkey" PRIMARY KEY ("id")
);

-- Canonical form: no leading zeros, digits only, bounded. Rejects '', ' 12', '007', 'abc'.
ALTER TABLE "iclock_employee_pins" ADD CONSTRAINT "iclock_employee_pins_pin_canonical"
    CHECK ("pin" ~ '^[1-9][0-9]{0,19}$');

-- One pin per employee.
CREATE UNIQUE INDEX "iclock_employee_pins_employeeId_key" ON "iclock_employee_pins"("employeeId");
-- THE RULE, as a real constraint (possible only because of D2): a pin resolves to exactly one
-- employee within a site. A collision is now a constraint violation surfaced as a clean API error,
-- not a silently-picked winner.
CREATE UNIQUE INDEX "iclock_employee_pins_site_pin_key" ON "iclock_employee_pins"("siteId", "pin");

ALTER TABLE "iclock_employee_pins" ADD CONSTRAINT "iclock_employee_pins_employeeId_fkey"
    FOREIGN KEY ("employeeId") REFERENCES "employees"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "iclock_employee_pins" ADD CONSTRAINT "iclock_employee_pins_siteId_fkey"
    FOREIGN KEY ("siteId") REFERENCES "iclock_sites"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- ---------------------------------------------------------------------------------------------------
-- Effective punches: one row per BURST, not per raw punch. Survivors of collapse only; raw stays
-- complete and is never mutated.
--
-- ANCHORING. "rawPunchId" is the punch that CREATED this row, and it never moves — it is the row's
-- stable identity and the UNIQUE idempotency hook, not necessarily the burst's earliest punch
-- ("burstFirstAt" tracks that, and can move backwards when a buffered flush delivers an earlier punch
-- after a later one). "effectiveRawPunchId"/"effectiveAt" carry the KEPT punch: the earliest for an IN
-- burst, the latest for an OUT burst. Separating identity from effect is what lets the row be written
-- on the first punch and sharpened in place as the burst extends — immediate visibility, no settling
-- delay — while re-running promotion stays a provable no-op.
-- ---------------------------------------------------------------------------------------------------
CREATE TABLE "iclock_punches" (
    "id" TEXT NOT NULL,
    -- Idempotency anchor: the burst's first raw punch. Re-running promotion is a no-op.
    "rawPunchId" TEXT NOT NULL,
    -- The punch whose timestamp is authoritative: the anchor for IN, the latest for OUT.
    "effectiveRawPunchId" TEXT NOT NULL,
    "deviceId" TEXT NOT NULL,
    "siteId" TEXT NOT NULL,
    -- Snapshotted from the employee at promotion time; survives a later company move.
    "companyId" TEXT NOT NULL,
    "employeeId" TEXT NOT NULL,
    "devicePin" TEXT NOT NULL,
    -- Interpreted from the device's wall-clock string in the SITE's timezone.
    "punchedAt" TIMESTAMPTZ NOT NULL,
    -- The authoritative instant: equals punchedAt for IN, the burst's last punch for OUT.
    "effectiveAt" TIMESTAMPTZ NOT NULL,
    -- Shift-day of the KEPT punch (derived from effectiveAt, never from the anchor) so a burst
    -- straddling the 04:00 cut is attributed to the day it actually counts for.
    "shiftDate" DATE NOT NULL,
    "area" TEXT NOT NULL,
    "direction" TEXT NOT NULL,
    -- Burst bookkeeping. burstCount counts DISTINCT RAW LINES, not times the person presented:
    -- V42's content dedupe already collapses byte-identical same-second repeats.
    "burstFirstAt" TIMESTAMPTZ NOT NULL,
    "burstLastAt" TIMESTAMPTZ NOT NULL,
    "burstCount" INTEGER NOT NULL DEFAULT 1,
    -- Surfaced, never silently resolved. NULL = clean.
    "anomaly" TEXT,
    "createdAt" TIMESTAMPTZ NOT NULL DEFAULT now(),
    "updatedAt" TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT "iclock_punches_pkey" PRIMARY KEY ("id")
);

ALTER TABLE "iclock_punches" ADD CONSTRAINT "iclock_punches_area_valid"
    CHECK ("area" IN ('GATE', 'CAFETERIA'));
ALTER TABLE "iclock_punches" ADD CONSTRAINT "iclock_punches_direction_valid"
    CHECK ("direction" IN ('IN', 'OUT', 'MIXED'));
ALTER TABLE "iclock_punches" ADD CONSTRAINT "iclock_punches_anomaly_valid"
    CHECK ("anomaly" IS NULL OR "anomaly" IN ('ANOMALY_OFFBOARDED', 'MIXED_NO_COLLAPSE', 'ADJACENT_LEADERS'));
-- A burst cannot run backwards, and a runaway window fails loudly rather than being written silently.
ALTER TABLE "iclock_punches" ADD CONSTRAINT "iclock_punches_burst_window"
    CHECK ("burstLastAt" >= "burstFirstAt" AND "burstLastAt" - "burstFirstAt" <= INTERVAL '1 hour');
ALTER TABLE "iclock_punches" ADD CONSTRAINT "iclock_punches_effective_within_burst"
    CHECK ("effectiveAt" >= "burstFirstAt" AND "effectiveAt" <= "burstLastAt");

CREATE UNIQUE INDEX "iclock_punches_rawPunchId_key" ON "iclock_punches"("rawPunchId");
-- The operator timeline is ordered by effect, not arrival.
CREATE INDEX "iclock_punches_site_effective_idx" ON "iclock_punches"("siteId", "effectiveAt" DESC);
CREATE INDEX "iclock_punches_employee_shift_idx" ON "iclock_punches"("employeeId", "shiftDate");
CREATE INDEX "iclock_punches_shiftDate_idx" ON "iclock_punches"("shiftDate");
-- Burst-join candidate lookup: same device + same subject, newest first.
CREATE INDEX "iclock_punches_device_pin_burst_idx"
    ON "iclock_punches"("deviceId", "devicePin", "burstLastAt" DESC);
CREATE INDEX "iclock_punches_anomaly_idx" ON "iclock_punches"("anomaly") WHERE "anomaly" IS NOT NULL;

ALTER TABLE "iclock_punches" ADD CONSTRAINT "iclock_punches_rawPunchId_fkey"
    FOREIGN KEY ("rawPunchId") REFERENCES "iclock_raw_punches"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "iclock_punches" ADD CONSTRAINT "iclock_punches_deviceId_fkey"
    FOREIGN KEY ("deviceId") REFERENCES "iclock_devices"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "iclock_punches" ADD CONSTRAINT "iclock_punches_siteId_fkey"
    FOREIGN KEY ("siteId") REFERENCES "iclock_sites"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "iclock_punches" ADD CONSTRAINT "iclock_punches_employeeId_fkey"
    FOREIGN KEY ("employeeId") REFERENCES "employees"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- ---------------------------------------------------------------------------------------------------
-- Burst membership. Explicit rather than inferred, for two reasons: it makes "has this raw punch been
-- promoted?" a single indexed lookup (exact idempotency, including for followers that produce no
-- effective row of their own), and it keeps iclock_raw_punches untouched — raw stays a pure append-only
-- record of what the device said.
-- ---------------------------------------------------------------------------------------------------
CREATE TABLE "iclock_punch_members" (
    "id" TEXT NOT NULL,
    "punchId" TEXT NOT NULL,
    "rawPunchId" TEXT NOT NULL,
    "createdAt" TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT "iclock_punch_members_pkey" PRIMARY KEY ("id")
);

-- One raw punch belongs to at most one burst. This is the idempotency contract for re-runs.
CREATE UNIQUE INDEX "iclock_punch_members_rawPunchId_key" ON "iclock_punch_members"("rawPunchId");
CREATE INDEX "iclock_punch_members_punchId_idx" ON "iclock_punch_members"("punchId");

ALTER TABLE "iclock_punch_members" ADD CONSTRAINT "iclock_punch_members_punchId_fkey"
    FOREIGN KEY ("punchId") REFERENCES "iclock_punches"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "iclock_punch_members" ADD CONSTRAINT "iclock_punch_members_rawPunchId_fkey"
    FOREIGN KEY ("rawPunchId") REFERENCES "iclock_raw_punches"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- ---------------------------------------------------------------------------------------------------
-- Diagnostics: the real client IP.
--
-- request.getRemoteAddr() records the Cloudflare EDGE address in production (172.71.x.x observed), not
-- the terminal, so the source-IP signal that identified the .aspx dialect on the LAN is unavailable
-- there. This column captures the forwarded header verbatim when present. Devices are identified by
-- SERIAL, never by IP; this is for diagnosis only.
-- ---------------------------------------------------------------------------------------------------
ALTER TABLE "iclock_request_logs" ADD COLUMN "xForwardedFor" TEXT;
