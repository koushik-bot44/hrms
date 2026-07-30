package com.ihrms.attendance;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * Working-day calendar for the viewer attendance analytics (§8a). Centralized + labeled so the rule is
 * swap-ready later (public holidays, Mon–Sat, …) — today it is simply <b>Mon–Fri</b> (weekends excluded;
 * holidays NOT excluded yet). Adherence and the unapproved-absence metric are both built on this basis.
 */
public final class AttendanceCalendar {

  private AttendanceCalendar() {}

  /** Human label of the working-days / adherence / absence rules — surfaced to the UI. */
  public static final String WORKING_DAYS_DEFINITION =
      "Working days = Mon–Fri in the month (weekends excluded; public holidays not excluded yet)."
          + " Adherence = present working days ÷ (working days − approved leave on working days)."
          + " Unapproved absence = a working day already in the PAST (before today, IST) with no session"
          + " and no approved leave.";

  /** True for Mon–Fri. */
  public static boolean isWorkingDay(LocalDate date) {
    DayOfWeek dow = date.getDayOfWeek();
    return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
  }

  /** All Mon–Fri dates in the month, in order. */
  public static List<LocalDate> workingDays(YearMonth ym) {
    return workingDays(ym.atDay(1), ym.atEndOfMonth());
  }

  /** All Mon–Fri dates in {@code [from, to]} inclusive, in order (the range basis for a custom report). */
  public static List<LocalDate> workingDays(LocalDate from, LocalDate to) {
    List<LocalDate> out = new ArrayList<>();
    for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
      if (isWorkingDay(d)) {
        out.add(d);
      }
    }
    return out;
  }
}
