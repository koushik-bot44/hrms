package com.ihrms.iclock;

import com.ihrms.attendance.ShiftConfig;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * "Missing OUT" membership, as a pure function.
 *
 * <p>Someone who arrived and never tapped out. Surfacing only — no punch is invented, no data is
 * mutated, and nothing here decides what the day is worth. Regularisation is P3's job; this exists so
 * that the operator finds out the next morning rather than when payroll disagrees with someone.
 *
 * <p>ONE DEFINITION, TWO SURFACES. The rule is the same unpaired-IN the day view already badges as
 * estimated: the person's last punch of the shift day is not a gate exit. Deriving it twice would let
 * the strip and the day view disagree about the same person on the same day.
 */
final class IclockMissingOut {

  private IclockMissingOut() {}

  /**
   * The most recently COMPLETED shift day.
   *
   * <p>The shift for date D runs {@code [D 19:00, D+1 04:00)}, so D is complete once the clock passes
   * D+1 04:00. Everything else falls out of that one comparison:
   *
   * <ul>
   *   <li>21:00 — the current shift day is live, so the completed one is yesterday.
   *   <li>05:00 — the shift that started yesterday ended an hour ago; that IS the completed one, and
   *       it is also what {@code shiftDateOf} still calls today.
   *   <li>12:00 — past the day cut, so the current shift day has not started yet; completed is the one
   *       before it.
   * </ul>
   *
   * <p>Written as "has this shift ended yet" rather than as a set of time-of-day branches, because the
   * branches are what get one case wrong and leave the section silently showing the wrong day.
   */
  static LocalDate completedShiftDay(Instant now, ZoneId zone) {
    LocalDate current = IclockShiftDay.of(now, zone);
    Instant endOfCurrent =
        current.plusDays(1).atTime(ShiftConfig.SHIFT_END).atZone(zone).toInstant();
    return now.isBefore(endOfCurrent) ? current.minusDays(1) : current;
  }

  /**
   * Whether this person's day ended without a gate exit.
   *
   * @param dayPunches the person's punches for one shift day, ASCENDING by effective time
   * @return true when they arrived and their last punch is not a GATE OUT
   */
  static boolean isMissingOut(List<PunchFacts> dayPunches) {
    if (dayPunches == null || dayPunches.isEmpty()) {
      return false;
    }
    // "Arrived" means at least one IN. Someone whose only punches are OUTs has a different problem —
    // an orphan exit — and pretending they are missing an OUT would be the wrong story about them.
    boolean arrived = dayPunches.stream().anyMatch(p -> "IN".equals(p.direction()));
    if (!arrived) {
      return false;
    }
    PunchFacts last = dayPunches.get(dayPunches.size() - 1);
    // A GATE OUT is the only punch that ends a day. A CAFETERIA OUT means they went back to their desk
    // — under the confirmed inversion that is an ARRIVAL at work, not a departure from the building —
    // so a day ending on one is exactly the stuck-in-office case this section is for.
    return !("GATE".equals(last.area()) && "OUT".equals(last.direction()));
  }

  /** The minimum a punch has to expose for this rule. Keeps the function free of entity types. */
  record PunchFacts(Instant at, String area, String direction) {}
}
