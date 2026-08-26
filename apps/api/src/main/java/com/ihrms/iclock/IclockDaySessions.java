package com.ihrms.iclock;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One person's day, split into the stretches they were AT WORK and the stretches they were on a
 * break — as a pure function.
 *
 * <p><b>This exists because the day view read a cafeteria break as a departure.</b> The pairer used
 * to branch on {@code direction} alone, so a CAFETERIA IN — which under the ratified inversion means
 * a break STARTS — was treated as an arrival. It orphaned the open work session as "estimated", and
 * the closing GATE OUT then had nothing to pair with and became a parentless exit. A perfectly
 * ordinary 9-hour night containing one coffee break rendered as "On site 0m" with two amber
 * Estimated badges, on the one screen an operator opens to check whether pairing worked.
 *
 * <p><b>The inversion, stated once.</b> "IN for cafeteria = OUT for work":
 *
 * <pre>
 *   GATE      IN   -> work starts
 *   GATE      OUT  -> work ends
 *   CAFETERIA IN   -> work PAUSES, break starts
 *   CAFETERIA OUT  -> break ends,  work RESUMES
 * </pre>
 *
 * <p>So a night with one break is three segments, not one broken one: work, break, work. Summing the
 * work segments gives time on site and summing the break segments gives {@code cafeteriaMin}, which
 * is the definition P2b's policy engine needs anyway.
 *
 * <p><b>Nothing is invented.</b> An unpaired stretch is flagged {@code estimated} and keeps its null
 * end, exactly as before — a missing punch is normal in an event stream, and closing it silently is
 * how a board starts lying. The mirror rule holds too: an OUT with no IN renders as an honest gap
 * with a null start rather than being dropped.
 */
final class IclockDaySessions {

  private IclockDaySessions() {}

  /** The minimum a punch has to expose. Keeps the rule free of entity types, like the other rules. */
  record Punch(Instant at, String area, String direction) {}

  /**
   * A stretch of the day.
   *
   * @param from null when the stretch has no start punch (an OUT with no IN)
   * @param to null when it has no end punch (still open, or the person never tapped out)
   * @param estimated true when either end is missing — surfaced, never silently closed
   * @param cafeteria true for a break, false for time at work
   */
  record Segment(Instant from, Instant to, boolean estimated, boolean cafeteria) {}

  private static boolean isCafeteria(Punch p) {
    return "CAFETERIA".equals(p.area());
  }

  /**
   * Splits a shift day's punches into work and break segments.
   *
   * @param dayPunches one person's punches for one shift day, ASCENDING by effective time
   */
  static List<Segment> segment(List<Punch> dayPunches) {
    List<Segment> out = new ArrayList<>();
    if (dayPunches == null || dayPunches.isEmpty()) {
      return out;
    }

    Instant workOpen = null;
    Instant breakOpen = null;

    for (Punch p : dayPunches) {
      boolean cafe = isCafeteria(p);
      boolean in = "IN".equals(p.direction());
      boolean outDir = "OUT".equals(p.direction());

      // A MIXED terminal carries no usable direction. Promotion already flags those punches; the
      // pairer skips them rather than guessing, because a guessed direction here would silently
      // fabricate or destroy a session.
      if (!in && !outDir) {
        continue;
      }

      if (!cafe && in) {
        // Arrived at work. A second arrival with one already open means the first was never closed.
        if (breakOpen != null) {
          out.add(new Segment(breakOpen, null, true, true)); // break that never ended
          breakOpen = null;
        }
        if (workOpen != null) {
          out.add(new Segment(workOpen, null, true, false));
        }
        workOpen = p.at();
      } else if (!cafe && outDir) {
        // Left the building — the only punch that ends a working day.
        if (breakOpen != null) {
          // Walked out straight from the cafeteria: the break ends here, and no work resumed.
          out.add(new Segment(breakOpen, p.at(), false, true));
          breakOpen = null;
        } else if (workOpen != null) {
          out.add(new Segment(workOpen, p.at(), false, false));
          workOpen = null;
        } else {
          out.add(new Segment(null, p.at(), true, false)); // an exit with no matching arrival
        }
        workOpen = null;
      } else if (cafe && in) {
        // THE INVERSION. Going into the cafeteria stops work and starts a break.
        if (workOpen != null) {
          out.add(new Segment(workOpen, p.at(), false, false));
          workOpen = null;
        }
        if (breakOpen != null) {
          out.add(new Segment(breakOpen, null, true, true)); // two entries, no exit between them
        }
        breakOpen = p.at();
      } else {
        // cafe && OUT — back at the desk. The break ends and work resumes from this instant.
        if (breakOpen != null) {
          out.add(new Segment(breakOpen, p.at(), false, true));
          breakOpen = null;
          workOpen = p.at();
        } else {
          // A return with no recorded departure. Surfaced as an estimated break, but work must NOT
          // be restarted here: assigning workOpen unconditionally overwrote a session that was
          // already open — someone at their desk since 19:00 lost that entire stretch, silently,
          // because of one unmatched cafeteria tap. If they were already working, they never
          // stopped; only somebody with no open session starts one here.
          out.add(new Segment(null, p.at(), true, true));
          if (workOpen == null) {
            workOpen = p.at();
          }
        }
      }
    }

    if (breakOpen != null) {
      out.add(new Segment(breakOpen, null, true, true));
    }
    if (workOpen != null) {
      out.add(new Segment(workOpen, null, true, false));
    }

    // Chronological, because this renders as a timeline. Segments are EMITTED when they close, so a
    // long work stretch that ends at 04:00 would otherwise sort below a short break that closed at
    // 22:30 and sat inside it. A segment with no start is placed at the instant it ended, which is
    // the only time it is known to have happened.
    out.sort(java.util.Comparator.comparing(s -> s.from() != null ? s.from() : s.to()));
    return out;
  }

  /**
   * The moment they arrived: the first GATE IN.
   *
   * <p>Deliberately not "the first IN of any kind". A CAFETERIA IN is a break starting, so treating
   * it as an arrival would report somebody as having turned up at the time they went for coffee.
   */
  static Instant firstArrival(List<Punch> dayPunches) {
    if (dayPunches == null) {
      return null;
    }
    return dayPunches.stream()
        .filter(p -> !isCafeteria(p) && "IN".equals(p.direction()))
        .map(Punch::at)
        .findFirst()
        .orElse(null);
  }

  /**
   * The moment they left for the day: the last GATE OUT.
   *
   * <p>The mirror of the above, and the reason it matters is concrete: "Last out" used to take the
   * latest OUT of any area, so for anybody who visited the cafeteria it displayed the time they came
   * BACK to their desk — a number that reads like a departure and is nothing of the kind.
   */
  static Instant lastDeparture(List<Punch> dayPunches) {
    if (dayPunches == null) {
      return null;
    }
    return dayPunches.stream()
        .filter(p -> !isCafeteria(p) && "OUT".equals(p.direction()))
        .map(Punch::at)
        .reduce((a, b) -> b)
        .orElse(null);
  }

  /** Total minutes on breaks that actually closed. An open break is not yet a measurement. */
  static long cafeteriaMinutes(List<Segment> segments) {
    long total = 0;
    for (Segment s : segments) {
      if (s.cafeteria() && s.from() != null && s.to() != null) {
        total += Duration.between(s.from(), s.to()).toMinutes();
      }
    }
    return total;
  }

  /** Total minutes actually at work — closed work segments only, for the same reason. */
  static long workedMinutes(List<Segment> segments) {
    long total = 0;
    for (Segment s : segments) {
      if (!s.cafeteria() && s.from() != null && s.to() != null) {
        total += Duration.between(s.from(), s.to()).toMinutes();
      }
    }
    return total;
  }
}
