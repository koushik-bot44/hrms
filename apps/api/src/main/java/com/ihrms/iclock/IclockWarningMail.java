package com.ihrms.iclock;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * The late-login warning mail, ported from the analyzer's template — as a pure function.
 *
 * <p>Composition only. Nothing here sends, looks up an address or touches a transport: this turns a
 * {@link IclockReportService.PersonReport} into a subject and a body, and is therefore testable
 * without a mail server and incapable of mailing anybody by accident.
 *
 * <p><b>Employee-direct, CC per company.</b> The letter is addressed to the person it concerns, with
 * their company's admin copied, which is how the analyzer sent it and how the recipients expect it.
 *
 * <p><b>The numbers come from the report, never recomputed.</b> A warning that quotes a different
 * late count from the report it came from is the fastest way to lose an argument with an employee who
 * has been keeping their own tally.
 */
final class IclockWarningMail {

  private IclockWarningMail() {}

  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

  /** A composed letter. No addresses, because who receives it is a routing decision, not a text one. */
  record Letter(String subject, String body, int lateDays, int lopDays) {}

  /**
   * Whether this person has earned a warning at all.
   *
   * <p>Gated on LOP rather than on lateness: the first three are explicitly permitted, so mailing
   * somebody about a permitted arrival is both wrong and the quickest way to get the whole mechanism
   * switched off. An excluded person never reaches here — the report already dropped them.
   */
  static boolean warrantsWarning(IclockReportService.PersonReport r) {
    return r.lopDays() > 0;
  }

  static String hm(long minutes) {
    return IclockPayrollExport.hm(minutes);
  }

  private static String dateList(List<LocalDate> dates) {
    if (dates == null || dates.isEmpty()) {
      return "  (none recorded)";
    }
    StringBuilder sb = new StringBuilder();
    for (LocalDate d : dates) {
      sb.append("  • ").append(d.format(DATE)).append('\n');
    }
    return sb.toString().stripTrailing();
  }

  /**
   * Composes the letter for one person.
   *
   * <p>The wording is the analyzer's, kept deliberately: these recipients have had this letter before
   * and rewriting the policy sentence would read as the policy itself having changed.
   */
  static Letter compose(IclockReportService.PersonReport r, String period) {
    String name = r.name() == null || r.name().isBlank() ? "Colleague" : r.name();
    String company = r.companyName() == null ? "the company" : r.companyName();

    String subject = "Attendance — late logins for " + period;

    String body =
        "Dear " + name + ",\n\n"
            + "This is a summary of your attendance for " + period + ".\n\n"
            + "• Late logins: " + r.lateDays() + " day(s)\n"
            + "• Total late time: " + hm(r.lateMin()) + "\n"
            + "• Average late: " + r.avgLateMin() + " minute(s)\n"
            + "• Days present: " + r.presentDays() + " of " + r.workingDays() + " working days\n"
            + "• Allowed break: " + hm(r.allowedBreakMin()) + " (1 hr/day x "
            + r.presentDays() + " days)\n"
            + "• Break taken: " + hm(r.breakMin()) + "\n"
            + "• Break exceeded: " + (r.excessBreakMin() > 0 ? hm(r.excessBreakMin()) : "Nil") + "\n"
            + "• Productive hours: " + hm(r.productiveMin()) + "\n"
            + "• Unproductive hours: " + hm(r.unproductiveMin()) + "\n\n"
            + "Late on:\n" + dateList(r.lateDates()) + "\n\n"
            + "As per the company's attendance policy, only the first three late logins are "
            + "permitted. From the fourth occurrence onwards, each late login is treated as one day "
            + "of Leave Without Pay (LOP). Based on this, you have exceeded the permissible limit by "
            + r.lopDays() + " day(s).\n\n"
            + "Please ensure you log in on time going forward.\n\n"
            + "Regards,\n"
            + company + " — HR";

    return new Letter(subject, body, r.lateDays(), r.lopDays());
  }
}
