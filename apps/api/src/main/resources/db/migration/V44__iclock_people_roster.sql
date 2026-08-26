-- V44: iClock P1b — the biometric system owns its OWN roster. Additive.
--
-- WHY THIS EXISTS. P1a resolved a punch by joining pin -> iclock_employee_pins -> employees. That
-- makes an IHRMS employee record a PRECONDITION for attributing a punch, and IHRMS currently holds 25
-- employees (7 approved) against a 206-person roster on the terminals. Under P1a the system is
-- therefore unusable: every punch dies at UNKNOWN_PIN. P1b inverts it — iclock_people is the roster
-- and the sole resolution source, and an IHRMS employee link becomes deferred ENRICHMENT that happens
-- as onboarding catches up.
--
-- SEQUENCING (verified, not assumed). This migration is deliberately shipped while iclock_punches is
-- EMPTY (confirmed 0 rows in production before writing this). Every statement below is a WIDENING —
-- DROP NOT NULL, a new nullable column, a new table — so nothing here can reject a write from either
-- the running P1a jar or the incoming P1b jar, and there is no window where the schema refuses live
-- ingest. Tightening (personId SET NOT NULL) is deliberately NOT done here: it would have to wait
-- until the P1b jar is the only instance running, and it buys nothing while the table is empty.

-- ---------------------------------------------------------------------------------------------------
-- The roster. One row per person enrolled on a terminal at a site.
-- ---------------------------------------------------------------------------------------------------
CREATE TABLE "iclock_people" (
    "id" TEXT NOT NULL,
    "siteId" TEXT NOT NULL,
    -- Canonical form, leading zeros stripped (D3) — the live fleet sends 000261/02919 padded.
    "pin" TEXT NOT NULL,
    -- NULLABLE on purpose. A person seeded from punch history alone has no name, and fabricating a
    -- placeholder is worse than surfacing "unnamed" in the console for a human to fix. Note this also
    -- applies to rows bridged from IHRMS: employees."fullName" is itself nullable (V4), so an
    -- INVITED-stage employee can be nameless too.
    "name" TEXT,
    -- NULLABLE and deliberately NOT UNIQUE. The real seed data contains one address shared by two
    -- different people, and a unique index would make importing it impossible. Duplicates are FLAGGED
    -- in the console, never blocking.
    "email" TEXT,
    -- Resolved IHRMS company when the seed's label matched one; null when it did not.
    "companyId" TEXT,
    -- The label VERBATIM from the import, kept whether or not it resolved. Never dropped: an
    -- unmatched company is information ("Combino IT" exists on the terminals but not in IHRMS"), and
    -- silently discarding it would make the gap invisible.
    "companyLabel" TEXT,
    "team" TEXT,
    "role" TEXT,
    -- P2 policy input. Carried through the import and stored, but NOT applied by P1b's pipeline.
    "lateExemptMin" INTEGER NOT NULL DEFAULT 0,
    "active" BOOLEAN NOT NULL DEFAULT true,
    -- ORTHOGONAL to "active", and the distinction is the point. "active" gates RESOLUTION: an inactive
    -- person's punches do not promote at all. This flag gates REPORTING only: the person resolves,
    -- promotes, and appears everywhere live — board, day view, overview — but P2's reports and warning
    -- mail will skip them. It exists because the legacy tool's "deleted" meant report-exclusion, not
    -- departure, and conflating the two would erase current employees from the floor.
    -- Behaviourally inert in P1b: captured and surfaced, never consulted by the pipeline.
    "excludedFromReports" BOOLEAN NOT NULL DEFAULT false,
    -- The deferred enrichment link. Null until an operator confirms a suggestion.
    "employeeId" TEXT,
    "createdAt" TIMESTAMPTZ NOT NULL DEFAULT now(),
    "updatedAt" TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT "iclock_people_pkey" PRIMARY KEY ("id")
);

-- Same canonical shape the pins table enforces, so the two can never disagree about what a pin is.
ALTER TABLE "iclock_people" ADD CONSTRAINT "iclock_people_pin_canonical"
    CHECK ("pin" ~ '^[1-9][0-9]{0,19}$');

-- THE resolution key: one person per pin per site.
CREATE UNIQUE INDEX "iclock_people_site_pin_key" ON "iclock_people"("siteId", "pin");
-- One roster person per IHRMS employee. Partial, so the many unlinked rows are unconstrained.
CREATE UNIQUE INDEX "iclock_people_employeeId_key"
    ON "iclock_people"("employeeId") WHERE "employeeId" IS NOT NULL;
CREATE INDEX "iclock_people_site_active_idx" ON "iclock_people"("siteId", "active");
CREATE INDEX "iclock_people_companyId_idx" ON "iclock_people"("companyId");
-- Case-insensitive email lookup drives the HIGH-confidence link suggestion.
CREATE INDEX "iclock_people_email_lower_idx" ON "iclock_people"(lower("email"));

ALTER TABLE "iclock_people" ADD CONSTRAINT "iclock_people_siteId_fkey"
    FOREIGN KEY ("siteId") REFERENCES "iclock_sites"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
-- RESTRICT, matching every other company reference in this schema (V1:172, V43:60). Company archival
-- here is a SOFT delete — V7 sets status='DELETED' and deletes no row — so ON DELETE never fires for
-- an archive anyway, and SET NULL would only mask a genuine hard-delete attempt.
ALTER TABLE "iclock_people" ADD CONSTRAINT "iclock_people_companyId_fkey"
    FOREIGN KEY ("companyId") REFERENCES "companies"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "iclock_people" ADD CONSTRAINT "iclock_people_employeeId_fkey"
    FOREIGN KEY ("employeeId") REFERENCES "employees"("id") ON DELETE SET NULL ON UPDATE CASCADE;
-- SET NULL here and not RESTRICT: deleting an IHRMS employee should un-link the roster person, not be
-- blocked by them. The person and their punch history are the biometric system's own record.

-- ---------------------------------------------------------------------------------------------------
-- Effective punches now hang off the ROSTER, not off an IHRMS employee.
-- ---------------------------------------------------------------------------------------------------
ALTER TABLE "iclock_punches" ADD COLUMN "personId" TEXT;

ALTER TABLE "iclock_punches" ADD CONSTRAINT "iclock_punches_personId_fkey"
    FOREIGN KEY ("personId") REFERENCES "iclock_people"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- The widenings. A punch can now resolve to a person who has NO IHRMS employee and whose company
-- label never matched, so neither column can stay mandatory.
ALTER TABLE "iclock_punches" ALTER COLUMN "employeeId" DROP NOT NULL;
ALTER TABLE "iclock_punches" ALTER COLUMN "companyId"  DROP NOT NULL;

CREATE INDEX "iclock_punches_person_shift_idx" ON "iclock_punches"("personId", "shiftDate");
-- The Live Board's hot path: everyone at a site on a shift-day, newest effect first.
CREATE INDEX "iclock_punches_site_shift_idx" ON "iclock_punches"("siteId", "shiftDate", "effectiveAt" DESC);

-- NOTE for whoever adds the NOT NULL later: personId is nullable in V44 ONLY because a rolling deploy
-- can briefly run the P1a jar, which does not know the column and would write NULL. Once P1b is the
-- sole running version, a follow-up migration may SET NOT NULL — but only after confirming
-- SELECT count(*) FROM "iclock_punches" WHERE "personId" IS NULL = 0.
