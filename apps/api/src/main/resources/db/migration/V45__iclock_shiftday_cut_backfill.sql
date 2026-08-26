-- V45: re-date every effective punch under the corrected shift-day cut. Data-only; no schema change.
--
-- WHY. The shift-day cut used to be 04:00 IST — the same instant the 19:00→04:00 shift ENDS. So a punch
-- at exactly 04:00, or any time after it, was filed on the NEXT shift-day. That is the closing OUT of
-- everyone who works a full shift: not an edge case, the normal case. The day they actually worked was
-- left with an unpaired IN and a null lastOut, and an orphan OUT appeared on the following day. Worked
-- minutes, breaks, lateness and LOP are all computed downstream of the shift-day, so the error reached
-- every one of them.
--
-- The cut is now the MIDPOINT of the non-working window (11:30 IST) — the point furthest from any real
-- punch, 7.5 hours clear on either side — and is derived from the shift in ShiftConfig rather than
-- written down, so moving the shift moves the cut with it.
--
-- WHAT THIS DOES NOT DO, and why that is correct. It does not re-run burst collapse. Burst membership
-- never consulted shiftDate: findBurstCandidate keys on (deviceId, devicePin, ±window) and
-- existsInterveningPunch on (personId, area, deviceId, instant range) — neither has a shiftDate
-- predicate. A pair straddling 04:00 within the burst window had therefore ALREADY merged before this
-- migration; only the shiftDate stamped on the result was wrong. No punch can newly merge, and the
-- verification block below proves it rather than assuming it.
--
-- IDEMPOTENT. shiftDate is a pure function of effectiveAt, so re-running changes nothing. Re-running is
-- how the assertions below stay meaningful.

-- ---------------------------------------------------------------------------------------------------
-- Capture the before-state so the migration can check its own work.
-- ---------------------------------------------------------------------------------------------------
CREATE TEMP TABLE _v45_before ON COMMIT DROP AS
SELECT
  (SELECT count(*) FROM "iclock_punches")        AS punches,
  (SELECT count(*) FROM "iclock_punch_members")  AS members,
  (SELECT count(*) FROM "iclock_raw_punches")    AS raws;

-- ---------------------------------------------------------------------------------------------------
-- The re-dating. shiftDate ALWAYS follows the kept (effective) punch — that is what
-- IclockPromotionService does on both create and burst take-over, so the backfill must agree.
--
-- The expression mirrors ShiftConfig.shiftDateOf exactly: local date in IST, minus one day when the
-- local time is before the cut. Written as a literal 11:30 here because SQL cannot read the Java
-- constant; IclockShiftDayBackfillTest pins the two together so they cannot drift.
-- ---------------------------------------------------------------------------------------------------
UPDATE "iclock_punches" p
   SET "shiftDate" = (
         CASE
           WHEN (p."effectiveAt" AT TIME ZONE 'Asia/Kolkata')::time < TIME '11:30'
           THEN (p."effectiveAt" AT TIME ZONE 'Asia/Kolkata')::date - 1
           ELSE (p."effectiveAt" AT TIME ZONE 'Asia/Kolkata')::date
         END
       ),
       "updatedAt" = now()
 WHERE p."shiftDate" IS DISTINCT FROM (
         CASE
           WHEN (p."effectiveAt" AT TIME ZONE 'Asia/Kolkata')::time < TIME '11:30'
           THEN (p."effectiveAt" AT TIME ZONE 'Asia/Kolkata')::date - 1
           ELSE (p."effectiveAt" AT TIME ZONE 'Asia/Kolkata')::date
         END
       );

-- ---------------------------------------------------------------------------------------------------
-- Verification. A migration that silently did the wrong thing to attendance data is worse than one
-- that fails, so these raise instead of reporting.
-- ---------------------------------------------------------------------------------------------------
DO $$
DECLARE
  v_drift   bigint;
  v_before  record;
  v_punches bigint;
  v_members bigint;
  v_raws    bigint;
  v_backfill bigint;
BEGIN
  -- 1. Every row now agrees with the rule.
  SELECT count(*) INTO v_drift
    FROM "iclock_punches" p
   WHERE p."shiftDate" IS DISTINCT FROM (
           CASE WHEN (p."effectiveAt" AT TIME ZONE 'Asia/Kolkata')::time < TIME '11:30'
                THEN (p."effectiveAt" AT TIME ZONE 'Asia/Kolkata')::date - 1
                ELSE (p."effectiveAt" AT TIME ZONE 'Asia/Kolkata')::date END);
  IF v_drift > 0 THEN
    RAISE EXCEPTION 'V45: % effective punches still disagree with the shift-day rule', v_drift;
  END IF;

  -- 2. Nothing was created, merged or destroyed. This is the proof that re-running burst collapse was
  --    unnecessary: if the cut had affected membership, these counts would have moved.
  SELECT * INTO v_before FROM _v45_before;
  SELECT count(*) INTO v_punches FROM "iclock_punches";
  SELECT count(*) INTO v_members FROM "iclock_punch_members";
  SELECT count(*) INTO v_raws    FROM "iclock_raw_punches";
  IF v_punches <> v_before.punches OR v_members <> v_before.members OR v_raws <> v_before.raws THEN
    RAISE EXCEPTION 'V45: row counts moved (punches %->%, members %->%, raw %->%) — re-dating must not merge or drop anything',
      v_before.punches, v_punches, v_before.members, v_members, v_before.raws, v_raws;
  END IF;

  -- 3. THE POLICY PIN, enforced in the migration itself. Backfill is off: no effective punch may descend
  --    from a raw row older than the earliest device claim. Re-dating must not smuggle one in.
  SELECT count(*) INTO v_backfill
    FROM "iclock_punches" p
    JOIN "iclock_punch_members" m ON m."punchId" = p."id"
    JOIN "iclock_raw_punches" r   ON r."id" = m."rawPunchId"
   WHERE r."receivedAt" < (SELECT min("claimedAt") FROM "iclock_devices" WHERE "status" = 'CLAIMED');
  IF v_backfill > 0 THEN
    RAISE EXCEPTION 'V45: % effective punches descend from pre-claim raw rows — backfill policy violated', v_backfill;
  END IF;

  RAISE NOTICE 'V45: shift-day cut backfill complete — % punches all agree with the 11:30 rule', v_punches;
END $$;
