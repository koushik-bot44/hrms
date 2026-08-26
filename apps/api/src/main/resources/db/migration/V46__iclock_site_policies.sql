-- V46: per-building policy settings. Additive.
--
-- The first two knobs are the "exceeding break" thresholds, which the console now edits directly. They
-- were @Value config, which meant changing them was a redeploy — fine for a constant, wrong for a
-- number an operator tunes against their own floor.
--
-- DELIBERATELY NAMED FOR POLICY, NOT FOR THE ALERT. P2's engine needs per-site shift start, late grace,
-- allowed break, weekly-off days and the payroll cycle start day, all resolved the same way. Creating
-- "iclock_break_alert_settings" now would guarantee a second, competing table in a fortnight; P2b adds
-- columns here instead.
--
-- RESOLUTION IS TOTAL WITHOUT A ROW. Every column is NOT NULL so a row that exists is complete, and the
-- resolver falls back to code defaults when no row exists. That matters beyond tidiness: tests create
-- sites straight through the repository, bypassing IclockAdminService.createSite, so a resolver that
-- REQUIRED a row would throw in every DB-gated test that ever makes a site. Seeding existing sites
-- below covers production; the fallback covers everything else.

CREATE TABLE "iclock_site_policies" (
    "id" TEXT NOT NULL,
    "siteId" TEXT NOT NULL,
    -- Away longer than this and the person shows in the "exceeding break" strip.
    "breakAlertMin" INTEGER NOT NULL DEFAULT 30,
    -- Away longer than THIS and a continuous absence is presumed a departure: the alert retires and the
    -- person simply stays in "Left". Without the cap, everyone who goes home early sits in the strip
    -- until the shift ends, and an alert nobody can clear is one the operator stops reading.
    "breakAlertMaxMin" INTEGER NOT NULL DEFAULT 120,
    "createdAt" TIMESTAMPTZ NOT NULL DEFAULT now(),
    "updatedAt" TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT "iclock_site_policies_pkey" PRIMARY KEY ("id")
);

-- One policy row per building. Also the ON CONFLICT target for an idempotent upsert.
CREATE UNIQUE INDEX "iclock_site_policies_siteId_key" ON "iclock_site_policies"("siteId");

ALTER TABLE "iclock_site_policies" ADD CONSTRAINT "iclock_site_policies_siteId_fkey"
    FOREIGN KEY ("siteId") REFERENCES "iclock_sites"("id") ON DELETE CASCADE ON UPDATE CASCADE;
-- CASCADE, unlike the RESTRICT used elsewhere in this schema: a policy row is not a record of fact. It
-- is configuration ABOUT a building and is meaningless once the building is gone, so it should follow
-- it rather than block its removal.

-- A break shorter than five minutes is not a break — the same floor the P2 rulebook uses for counting
-- OUT->IN gaps — and a cap at or below the threshold would mean every alert retires the instant it
-- fires. Bean validation mirrors both of these so a bad value is a 400 with a readable message rather
-- than a 500: Hibernate's ddl-auto:validate checks tables, columns and types, NOT check constraints, so
-- a CHECK on its own surfaces as DataIntegrityViolationException.
ALTER TABLE "iclock_site_policies" ADD CONSTRAINT "iclock_site_policies_break_min_sane"
    CHECK ("breakAlertMin" >= 5);
ALTER TABLE "iclock_site_policies" ADD CONSTRAINT "iclock_site_policies_break_max_after_min"
    CHECK ("breakAlertMaxMin" > "breakAlertMin");

-- Seed every existing building with the defaults, so production starts from a real row rather than the
-- fallback and the console has something concrete to show.
INSERT INTO "iclock_site_policies" ("id", "siteId")
SELECT 'sp_' || substr(md5(random()::text || s."id"), 1, 22), s."id"
  FROM "iclock_sites" s
 WHERE NOT EXISTS (
   SELECT 1 FROM "iclock_site_policies" p WHERE p."siteId" = s."id");
