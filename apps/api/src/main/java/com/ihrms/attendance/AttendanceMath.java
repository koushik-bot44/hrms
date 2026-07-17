package com.ihrms.attendance;

import com.ihrms.domain.model.AttendanceBreak;
import com.ihrms.domain.model.AttendanceSession;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The single source of the worked/break time computation (§8a) — reused by {@link AttendanceService}
 * (the employee/manager views) and the read-only viewer analytics so worked time is NEVER computed two
 * different ways. Worked seconds = a COMPLETED session's clock-in→clock-out duration MINUS its completed
 * breaks (open session contributes 0; open breaks are ignored). All values are UTC-instant deltas; the
 * shift-day/month grouping is done by the caller off the persisted {@code shiftDate}.
 */
public final class AttendanceMath {

  private AttendanceMath() {}

  /** Sum of a session's COMPLETED breaks, in seconds (open breaks excluded). */
  public static long breakSeconds(AttendanceSession s, Map<String, List<AttendanceBreak>> breaksById) {
    return breaksById.getOrDefault(s.getId(), List.of()).stream()
        .filter(b -> !b.isOpen())
        .mapToLong(b -> Duration.between(b.getBreakStartAt(), b.getBreakEndAt()).getSeconds())
        .sum();
  }

  /** Worked seconds for a session: duration minus completed breaks; 0 while the session is open. */
  public static long workedSeconds(AttendanceSession s, Map<String, List<AttendanceBreak>> breaksById) {
    if (s.isOpen()) {
      return 0;
    }
    long duration = Duration.between(s.getClockInAt(), s.getClockOutAt()).getSeconds();
    return Math.max(0, duration - breakSeconds(s, breaksById));
  }
}
