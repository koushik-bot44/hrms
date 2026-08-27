package com.ihrms.iclock;

import java.time.LocalDate;
import java.util.List;

/**
 * The payroll export, as a pure function over a report.
 *
 * <p>Deliberately NOT a second computation. It formats {@link IclockReportService.MonthlyReport} and
 * nothing else, so the CSV, the on-screen report and the warning mail cannot disagree about how many
 * days somebody lost — which is exactly the failure that made the spreadsheet era untrustworthy.
 *
 * <p>Minutes are rendered as {@code H:MM} for people and left as raw minutes in their own columns for
 * machines. Payroll systems parse; humans read; giving each what it needs beats a single ambiguous
 * column that a spreadsheet will silently reinterpret as a date.
 */
final class IclockPayrollExport {

  private IclockPayrollExport() {}

  static final List<String> HEADERS = List.of(
      "Pin", "Name", "Company", "Team", "Shift",
      "Working days", "Present", "Absent", "Weekly off", "Holiday",
      "Worked (h:mm)", "Worked (min)",
      "Expected (h:mm)", "Expected (min)", "Worked %",
      "Break (h:mm)", "Break (min)", "Excess break (h:mm)", "Excess break (min)",
      "Late days", "Late total (h:mm)", "Late total (min)", "Avg late (min)",
      "LOP days", "Days missing a punch", "Late dates");

  /** {@code 505} to {@code 8:25}. Never a decimal: "8.4 hours" invites being read as 8h40. */
  static String hm(long minutes) {
    long m = Math.max(0, minutes);
    return (m / 60) + ":" + String.format("%02d", m % 60);
  }

  /**
   * One CSV field, quoted only when it has to be.
   *
   * <p>Names in this data contain commas and the odd double quote, and one unescaped field shifts
   * every column after it — a payroll file that is silently one column out is worse than one that
   * fails to open.
   */
  static String csv(String raw) {
    String v = raw == null ? "" : raw;
    if (v.indexOf(',') < 0 && v.indexOf('"') < 0 && v.indexOf('\n') < 0 && v.indexOf('\r') < 0) {
      return v;
    }
    return '"' + v.replace("\"", "\"\"") + '"';
  }

  private static String dates(List<LocalDate> d) {
    return d == null || d.isEmpty() ? "" : d.stream().map(LocalDate::toString).reduce((a, b) -> a + " " + b).orElse("");
  }

  static String toCsv(IclockReportService.MonthlyReport report) {
    StringBuilder sb = new StringBuilder();
    sb.append(String.join(",", HEADERS)).append('\n');
    for (IclockReportService.PersonReport r : report.rows()) {
      sb.append(csv(r.pin())).append(',')
          .append(csv(r.name())).append(',')
          .append(csv(r.companyName())).append(',')
          .append(csv(r.team())).append(',')
          .append(csv(r.shiftProfile())).append(',')
          .append(r.workingDays()).append(',')
          .append(r.presentDays()).append(',')
          .append(r.absentDays()).append(',')
          .append(r.weeklyOffDays()).append(',')
          .append(r.holidayDays()).append(',')
          .append(hm(r.workedMin())).append(',')
          .append(r.workedMin()).append(',')
          .append(hm(r.expectedWorkMin())).append(',')
          .append(r.expectedWorkMin()).append(',')
          .append(r.workedPct()).append(',')
          .append(hm(r.breakMin())).append(',')
          .append(r.breakMin()).append(',')
          .append(hm(r.excessBreakMin())).append(',')
          .append(r.excessBreakMin()).append(',')
          .append(r.lateDays()).append(',')
          .append(hm(r.lateMin())).append(',')
          .append(r.lateMin()).append(',')
          .append(r.avgLateMin()).append(',')
          .append(r.lopDays()).append(',')
          .append(r.daysWithMissingPunch()).append(',')
          .append(csv(dates(r.lateDates())))
          .append('\n');
    }
    return sb.toString();
  }

  /** {@code attendance-2026-08-Orion-Towers.csv} — sortable, and says what it is without opening. */
  static String filename(IclockReportService.MonthlyReport report) {
    String site = report.siteName() == null ? "site" : report.siteName();
    return "attendance-" + report.period().label() + "-" + site.replaceAll("[^A-Za-z0-9]+", "-") + ".csv";
  }
}
