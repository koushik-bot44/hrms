-- V47: per-person shift assignment. Additive, and deliberately inert on the data it touches.
--
-- WHY THIS EXISTS. The shift-day cut has been a single global constant, which was true while everyone
-- worked 19:00->04:00 and became wrong the moment they did not. The daily terminal report names a
-- second shift ("GS", 09:00-18:00). Under the night cut a 09:00 arrival files one shift-day EARLIER
-- than the 18:00 departure that follows it, so a day-shift person never pairs: every day of theirs
-- reads as an unpaired IN plus an orphan OUT, and worked minutes, lateness and LOP are all computed
-- downstream of that. It is the same defect P1b.1 fixed at 04:00, arriving from the other end of the
-- clock.
--
-- THE DEFAULT IS CURRENT BEHAVIOUR. Every existing person defaults to NIGHT, which reproduces exactly
-- what the global constant does today, so this migration cannot re-date a single punch. Nobody moves to
-- DAY except by an explicit, audited assignment from a confirmed list. That matters here more than
-- usual: the pins labelled GS are enrolled THIS WEEK and their punch history is onboarding noise, so
-- neither the label nor the histogram is evidence yet. Guessing from punch times would break attribution
-- that currently works for 774 of 775 punches.

ALTER TABLE "iclock_people"
    ADD COLUMN "shiftProfile" TEXT NOT NULL DEFAULT 'NIGHT';

-- Two profiles today. A CHECK rather than an enum type so P2b can widen it in one additive statement.
-- Bean validation mirrors this so a bad value is a 400 with a readable message rather than a 500:
-- ddl-auto:validate checks tables, columns and types, NOT check constraints, so a CHECK on its own
-- surfaces as DataIntegrityViolationException.
ALTER TABLE "iclock_people" ADD CONSTRAINT "iclock_people_shiftProfile_known"
    CHECK ("shiftProfile" IN ('NIGHT', 'DAY'));

-- The board and the reports both filter a roster by profile; on a 255-person site that is a sequential
-- scan per refresh, and the board refreshes every 30 seconds.
CREATE INDEX "iclock_people_siteId_shiftProfile_idx"
    ON "iclock_people"("siteId", "shiftProfile");


-- The profile DEFINITIONS live in the policy lineage, as V46 said they would: P2b's engine needs shift
-- start, late grace, weekly-off days and the payroll cycle start resolved the same way, and creating a
-- separate "iclock_shift_profiles" table now would guarantee two competing sources of the same fact.
--
-- Every column is NOT NULL with a default, so a row that exists is complete and the resolver falls back
-- to code defaults when no row exists at all. That is not tidiness: tests create sites straight through
-- the repository, so a resolver that REQUIRED a row would throw in every DB-gated test that makes one.
ALTER TABLE "iclock_site_policies"
    ADD COLUMN "nightStart"       TIME    NOT NULL DEFAULT '19:00',
    ADD COLUMN "nightEnd"         TIME    NOT NULL DEFAULT '04:00',
    ADD COLUMN "nightLateGraceMin" INTEGER NOT NULL DEFAULT 15,
    ADD COLUMN "dayStart"         TIME    NOT NULL DEFAULT '09:00',
    ADD COLUMN "dayEnd"           TIME    NOT NULL DEFAULT '18:00',
    ADD COLUMN "dayLateGraceMin"  INTEGER NOT NULL DEFAULT 15;

-- A zero-length shift would put the shift-day cut on top of the shift itself and every punch would land
-- on the wrong day. Start and end are otherwise unconstrained: which one is larger is what makes a
-- profile overnight, and both directions are legitimate.
ALTER TABLE "iclock_site_policies" ADD CONSTRAINT "iclock_site_policies_night_has_length"
    CHECK ("nightStart" <> "nightEnd");
ALTER TABLE "iclock_site_policies" ADD CONSTRAINT "iclock_site_policies_day_has_length"
    CHECK ("dayStart" <> "dayEnd");
-- Grace is a tolerance, not a second shift. Negative would mean "late before you were due".
ALTER TABLE "iclock_site_policies" ADD CONSTRAINT "iclock_site_policies_night_grace_sane"
    CHECK ("nightLateGraceMin" >= 0 AND "nightLateGraceMin" <= 240);
ALTER TABLE "iclock_site_policies" ADD CONSTRAINT "iclock_site_policies_day_grace_sane"
    CHECK ("dayLateGraceMin" >= 0 AND "dayLateGraceMin" <= 240);


-- POST-CONDITION. This migration must not move anybody. If a person somehow lands on a profile other
-- than NIGHT during the migration itself, their punches would be re-dated by the next promotion pass
-- against a shift they were never confirmed to work, so fail the deploy rather than discover it later.
DO $$
DECLARE moved BIGINT;
BEGIN
    SELECT count(*) INTO moved FROM "iclock_people" WHERE "shiftProfile" <> 'NIGHT';
    IF moved > 0 THEN
        RAISE EXCEPTION
            'V47 must leave every person on NIGHT (current behaviour); % row(s) are not', moved;
    END IF;
END $$;
