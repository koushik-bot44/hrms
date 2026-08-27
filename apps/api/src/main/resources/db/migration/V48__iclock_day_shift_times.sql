-- V48: the DAY profile is 10:00-19:00, not 09:00-18:00. Additive; corrects V47's placeholder.
--
-- V47 shipped 09:00-18:00 because that is what the terminal report's "GS" label implied and no
-- confirmed hours existed yet. The operator has now confirmed the real shift, and the difference is
-- not cosmetic: the shift-day CUT is derived from the shift, so moving the hours moves the cut from
-- 01:30 to 02:30. A day-shift person finishing at 19:30 files on the correct day under one and the
-- wrong day under the other.
--
-- SAFE TO RUN AS A PLAIN UPDATE. Nobody is on the DAY profile yet — V47's own post-condition
-- guaranteed every person landed on NIGHT, and the console had no way to change that until now — so
-- this cannot re-date an existing punch. The post-condition below re-checks rather than assuming it.

DO $$
DECLARE on_day BIGINT;
BEGIN
    SELECT count(*) INTO on_day FROM "iclock_people" WHERE "shiftProfile" = 'DAY';
    IF on_day > 0 THEN
        RAISE EXCEPTION
            'V48 changes the DAY shift hours and would silently re-date punches for the % person(s) '
            'already on it. Reassign them after this migration instead.', on_day;
    END IF;
END $$;

-- The stored definitions, for every building that has a policy row.
UPDATE "iclock_site_policies"
   SET "dayStart"  = TIME '10:00',
       "dayEnd"    = TIME '19:00',
       "updatedAt" = now()
 WHERE "dayStart" = TIME '09:00' AND "dayEnd" = TIME '18:00';

-- And the column defaults, so a building created tomorrow starts from the confirmed hours rather
-- than inheriting the placeholder. A default left stale is a bug that only appears months later,
-- when somebody adds a site and wonders why its day shift disagrees with every other one.
ALTER TABLE "iclock_site_policies"
    ALTER COLUMN "dayStart" SET DEFAULT '10:00',
    ALTER COLUMN "dayEnd"   SET DEFAULT '19:00';

-- Grace is unchanged at 15 minutes for both profiles, so DAY is late after 10:15 exactly as NIGHT is
-- after 19:15. Stated here because "late after 10:15" is the number the operator will quote, and it
-- is derived rather than stored.

DO $$
DECLARE wrong BIGINT;
BEGIN
    SELECT count(*) INTO wrong FROM "iclock_site_policies"
     WHERE "dayStart" <> TIME '10:00' OR "dayEnd" <> TIME '19:00';
    IF wrong > 0 THEN
        RAISE EXCEPTION 'V48 left % policy row(s) on day hours other than 10:00-19:00', wrong;
    END IF;
END $$;
