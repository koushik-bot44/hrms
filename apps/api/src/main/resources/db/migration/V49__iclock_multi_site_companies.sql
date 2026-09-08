-- V49: a company may work in more than one building. Widening; nothing is narrowed.
--
-- (V48 was taken by the day-shift correction, so the D2 unit lands here. Numbers are ordering, not
-- identity.)
--
-- D2 said "a company belongs to exactly ONE site". That premise is now factually false: Screatives
-- Software Services and Sphinix Technologies both have staff in Orion Towers AND Building No.9. It
-- was never a law of the domain, only a simplification that made employee -> site resolution a single
-- lookup, and it has stopped being true.
--
-- SHIPPED AS ONE UNIT WITH THE CODE, deliberately. Dropping the index alone changes nothing — the
-- refusal lives in application code and fires first — and removing the refusal alone would hit a
-- unique violation. Half of this change is worse than neither half.

-- ---------------------------------------------------------------- site <-> company

-- The physical expression of D2.
DROP INDEX IF EXISTS "iclock_site_companies_companyId_key";

-- Replaced, NOT simply removed. V43 created only the companyId unique index plus a non-unique one on
-- siteId, so dropping it would leave the membership table with no uniqueness at all and let the same
-- (site, company) pair be inserted twice — which would double every per-company count that joins
-- through it.
CREATE UNIQUE INDEX IF NOT EXISTS "iclock_site_companies_site_company_key"
    ON "iclock_site_companies"("siteId", "companyId");

-- ---------------------------------------------------------------- people <-> employee

-- One IHRMS employee may now hold a roster row in each building they work in. The old global unique
-- made that impossible: linking somebody at Building 9 failed because their Orion row already claimed
-- the employee, so exactly the dual-site people this migration exists for were the ones who could not
-- be linked.
--
-- Still unique WITHIN a site: two roster rows in one building pointing at one employee would make
-- "who is this punch" ambiguous, which is the thing the original index was actually protecting.
DROP INDEX IF EXISTS "iclock_people_employeeId_key";

CREATE UNIQUE INDEX IF NOT EXISTS "iclock_people_site_employee_key"
    ON "iclock_people"("siteId", "employeeId") WHERE "employeeId" IS NOT NULL;

-- ---------------------------------------------------------------- device role validity

-- WHEN a terminal took its current role. Promotion reads area/direction as they are NOW, which is
-- correct for live punches — they arrive after the claim by construction — and wrong for any
-- backfill of pre-adoption history, which would be stamped with today's role.
--
-- Nullable and unenforced: this records what is known, and for every existing device that is the
-- claim itself. It exists so a future backfill can ASSERT the role held for the window it is about to
-- promote, rather than assuming it. Nothing reads it yet, and nothing should until backfill has a
-- ruling.
ALTER TABLE "iclock_devices"
    ADD COLUMN IF NOT EXISTS "roleValidFrom" TIMESTAMPTZ;

-- Seed from claimedAt: a claimed terminal has held its current role since it was adopted, which is
-- the only interval anybody has evidence for. For the two 2A units that evidence runs further back —
-- 30 unbroken nights of arrival/departure signature to 2026-07-16 — but claimedAt is the conservative
-- floor and understating it can only make a future backfill refuse, never let it through wrongly.
UPDATE "iclock_devices"
   SET "roleValidFrom" = "claimedAt"
 WHERE "roleValidFrom" IS NULL AND "claimedAt" IS NOT NULL;

-- ---------------------------------------------------------------- post-conditions

DO $$
DECLARE n BIGINT;
BEGIN
    SELECT count(*) INTO n FROM pg_indexes
     WHERE indexname = 'iclock_site_companies_site_company_key';
    IF n <> 1 THEN
        RAISE EXCEPTION 'V49 must leave (siteId, companyId) unique on iclock_site_companies';
    END IF;

    SELECT count(*) INTO n FROM pg_indexes WHERE indexname = 'iclock_people_site_employee_key';
    IF n <> 1 THEN
        RAISE EXCEPTION 'V49 must leave (siteId, employeeId) unique on iclock_people';
    END IF;

    -- Widening must not have lost a link or a person.
    SELECT count(*) INTO n FROM "iclock_site_companies" a
      JOIN "iclock_site_companies" b
        ON a."siteId" = b."siteId" AND a."companyId" = b."companyId" AND a."id" <> b."id";
    IF n > 0 THEN
        RAISE EXCEPTION 'V49 found % duplicate (site, company) link(s); the new index should have refused them', n;
    END IF;
END $$;
