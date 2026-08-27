package com.ihrms.iclock;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The payroll CSV and the warning letter — both formatters over one report, never a second sum. */
class IclockPayrollExportTest {

  private static IclockReportService.PersonReport row(
      String pin, String name, String company, int lateDays, int lopDays) {
    return new IclockReportService.PersonReport(
        "p_" + pin, pin, name, company, "Kiran Team", "NIGHT",
        20, 1, 8, 2, 21,
        9000, 1400, 900, 1200, 200,
        lateDays, 150, 30, 1,
        9600, 9450, 350, 87, 94, lopDays,
        List.of(LocalDate.parse("2026-08-03"), LocalDate.parse("2026-08-11")));
  }

  private static IclockReportService.MonthlyReport report(
      List<IclockReportService.PersonReport> rows) {
    return new IclockReportService.MonthlyReport(
        "site1", "Orion Towers",
        IclockReportService.periodFor(YearMonth.parse("2026-08"), 1),
        rows.size(), 0, rows, List.of());
  }

  // ------------------------------------------------------------------ format

  @Test
  void minutesRenderAsHoursAndMinutesNeverAsADecimal() {
    // "8.4 hours" gets read as eight hours forty by half the people who see it.
    assertThat(IclockPayrollExport.hm(505)).isEqualTo("8:25");
    assertThat(IclockPayrollExport.hm(60)).isEqualTo("1:00");
    assertThat(IclockPayrollExport.hm(9)).isEqualTo("0:09");
    assertThat(IclockPayrollExport.hm(0)).isEqualTo("0:00");
    assertThat(IclockPayrollExport.hm(-5)).as("never negative").isEqualTo("0:00");
  }

  @Test
  void aNameWithACommaDoesNotShiftEveryColumnAfterIt() {
    // The real failure this guards: one unescaped field and the payroll file is silently one column
    // out, which is worse than a file that refuses to open.
    assertThat(IclockPayrollExport.csv("Kumar, Anil")).isEqualTo("\"Kumar, Anil\"");
    assertThat(IclockPayrollExport.csv("She said \"hi\"")).isEqualTo("\"She said \"\"hi\"\"\"");
    assertThat(IclockPayrollExport.csv("plain")).isEqualTo("plain");
    assertThat(IclockPayrollExport.csv(null)).isEmpty();
  }

  @Test
  void everyRowHasExactlyAsManyFieldsAsThereAreHeaders() {
    String csv = IclockPayrollExport.toCsv(report(List.of(
        row("101", "Kumar, Anil", "Screatives Software Services", 5, 2),
        row("102", "Plain Name", "Sphinix Technologies", 1, 0))));

    String[] lines = csv.strip().split("\n");
    assertThat(lines).hasSize(3);
    assertThat(lines[0].split(",", -1)).hasSameSizeAs(IclockPayrollExport.HEADERS.toArray());
    for (String line : lines) {
      assertThat(countFields(line))
          .as("row must line up with the header: %s", line)
          .isEqualTo(IclockPayrollExport.HEADERS.size());
    }
  }

  /** Counts CSV fields respecting quotes, the way a spreadsheet actually parses. */
  private static int countFields(String line) {
    int n = 1;
    boolean quoted = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (c == '"') {
        quoted = !quoted;
      } else if (c == ',' && !quoted) {
        n++;
      }
    }
    return n;
  }

  @Test
  void theFilenameSaysWhatItIsWithoutBeingOpened() {
    assertThat(IclockPayrollExport.filename(report(List.of(row("1", "A", "C", 0, 0)))))
        .isEqualTo("attendance-2026-08-Orion-Towers.csv");
  }

  // ------------------------------------------------------------------ period

  @Test
  void aPlainCalendarMonthIsTheWholeMonth() {
    var p = IclockReportService.periodFor(YearMonth.parse("2026-08"), 1);
    assertThat(p.from()).isEqualTo(LocalDate.parse("2026-08-01"));
    assertThat(p.to()).isEqualTo(LocalDate.parse("2026-08-31"));
  }

  @Test
  void aCycleIsLabelledByItsENDMonth() {
    // Ratified: 26 July to 25 August is "August", the month it is paid in and the month everyone
    // calls it. Labelling it July would put two meanings on one word in the same conversation.
    var p = IclockReportService.periodFor(YearMonth.parse("2026-08"), 26);
    assertThat(p.label()).isEqualTo(YearMonth.parse("2026-08"));
    assertThat(p.from()).isEqualTo(LocalDate.parse("2026-07-26"));
    assertThat(p.to()).isEqualTo(LocalDate.parse("2026-08-25"));
  }

  // ------------------------------------------------------------------ the letter

  @Test
  void nobodyIsWarnedForAPermittedLateArrival() {
    // The first three are explicitly allowed. Mailing somebody about one is both wrong and the
    // fastest way to get the whole mechanism switched off.
    assertThat(IclockWarningMail.warrantsWarning(row("1", "A", "C", 3, 0))).isFalse();
    assertThat(IclockWarningMail.warrantsWarning(row("1", "A", "C", 4, 1))).isTrue();
  }

  @Test
  void theLetterQuotesTheReportsOwnNumbers() {
    var r = row("101", "Anil Kumar", "Screatives Software Services", 5, 2);
    var letter = IclockWarningMail.compose(r, "August 2026");

    assertThat(letter.subject()).contains("August 2026");
    assertThat(letter.body())
        .contains("Dear Anil Kumar")
        .contains("Late logins: 5 day(s)")
        .contains("Total late time: 2:30")
        .contains("Average late: 30 minute(s)")
        .contains("Days present: 20 of 21 working days")
        .contains("Allowed break: 20:00 (1 hr/day x 20 days)")
        .contains("Break exceeded: 3:20")
        .contains("exceeded the permissible limit by 2 day(s)")
        .contains("03 Aug 2026")
        .contains("11 Aug 2026")
        .contains("Screatives Software Services — HR");
    assertThat(letter.lopDays()).isEqualTo(2);
  }

  @Test
  void aClearBreakRecordSaysNilRatherThanZero() {
    var clean = new IclockReportService.PersonReport(
        "p", "1", "Clean Person", "Co", null, "NIGHT",
        20, 0, 8, 0, 20,
        9600, 900, 0, 1200, 0,
        4, 40, 10, 0, 9600, 10000, 40, 99, 100, 1, List.of());
    assertThat(IclockWarningMail.compose(clean, "August 2026").body())
        .contains("Break exceeded: Nil");
  }

  @Test
  void anUnnamedPersonIsAddressedPolitelyRatherThanAsNull() {
    var r = new IclockReportService.PersonReport(
        "p", "9", null, null, null, "NIGHT",
        1, 0, 0, 0, 1, 0, 0, 0, 60, 0, 4, 10, 3, 0, 480, 480, 10, 90, 0, 1, List.of());
    var letter = IclockWarningMail.compose(r, "August 2026");
    assertThat(letter.body()).startsWith("Dear Colleague,").contains("the company — HR");
  }
}
