package com.ihrms.iclock;

import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.IclockPerson;
import com.ihrms.domain.model.IclockPunch;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.IclockPersonRepository;
import com.ihrms.domain.repository.IclockPunchRepository;
import com.ihrms.domain.repository.IclockSiteRepository;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Monthly attendance reporting — the analyzer's output, derived from the pipeline.
 *
 * <p><b>Structurally read-only.</b> Every method here is {@code @Transactional(readOnly = true)},
 * which puts Hibernate in {@code FlushMode.MANUAL}: even if a managed entity were touched by accident,
 * nothing can reach the database. That is the standing rule for preview and report paths, and it
 * exists because a dry run once rewrote a roster through dirty checking alone.
 *
 * <p><b>Excluded people never appear.</b> {@code excludedFromReports} is applied once, here, at the
 * point the roster is read — not in each renderer. The legacy tool's "deleted" flag meant exactly this
 * and nothing more: those people still punch, still show on the live board, and are simply not
 * reported on.
 */
@Service
public class IclockReportService {

  private final IclockPersonRepository people;
  private final IclockPunchRepository punches;
  private final IclockSiteRepository sites;
  private final CompanyRepository companies;
  private final IclockSitePolicyService policies;

  public IclockReportService(
      IclockPersonRepository people,
      IclockPunchRepository punches,
      IclockSiteRepository sites,
      CompanyRepository companies,
      IclockSitePolicyService policies) {
    this.people = people;
    this.punches = punches;
    this.sites = sites;
    this.companies = companies;
    this.policies = policies;
  }

  /**
   * The span of shift days a labelled period covers.
   *
   * <p><b>The label is the END month</b>, ratified. A cycle running 26 July to 25 August is "August",
   * because that is the month it is paid in and the month everyone calls it. Labelling it July would
   * put two different meanings on one word in the same conversation.
   *
   * @param cycleStartDay day of month the cycle opens on; 1 means a plain calendar month
   */
  public static Period periodFor(YearMonth label, int cycleStartDay) {
    if (cycleStartDay <= 1) {
      return new Period(label, label.atDay(1), label.atEndOfMonth());
    }
    LocalDate to = label.atDay(Math.min(cycleStartDay - 1, label.lengthOfMonth()));
    LocalDate from = to.minusMonths(1).plusDays(1);
    return new Period(label, from, to);
  }

  /** A labelled span of shift days. */
  public record Period(YearMonth label, LocalDate from, LocalDate to) {}

  /** One person's month. */
  public record PersonReport(
      String personId,
      String pin,
      String name,
      String companyName,
      String team,
      String shiftProfile,
      int presentDays,
      int absentDays,
      int weeklyOffDays,
      int holidayDays,
      int workingDays,
      long workedMin,
      long breakMin,
      long cafeteriaMin,
      /** presentDays x the paid hour — quoted verbatim by the warning mail, so it lives here. */
      long allowedBreakMin,
      long excessBreakMin,
      int lateDays,
      long lateMin,
      long avgLateMin,
      int daysWithMissingPunch,
      long expectedWorkMin,
      long productiveMin,
      long unproductiveMin,
      int productivePct,
      int workedPct,
      int lopDays,
      List<LocalDate> lateDates) {}

  /** The whole building, plus the per-company grouping the console shows. */
  public record MonthlyReport(
      String siteId,
      String siteName,
      Period period,
      int peopleReported,
      int peopleExcluded,
      List<PersonReport> rows,
      List<CompanyRollup> byCompany) {}

  /** Per-company totals — the shape the operator mails to each company's admin. */
  public record CompanyRollup(
      String companyName, int people, int lateDays, int lopDays, long excessBreakMin) {}

  /**
   * Builds the month for one building.
   *
   * <p>Days with no punches at all still appear: a person absent for a week is the single most
   * important thing a report can say, and a report built only from punches cannot say it.
   */
  @Transactional(readOnly = true)
  public MonthlyReport monthly(String siteId, YearMonth label) {
    var site = sites.findById(siteId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Building not found"));
    ZoneId zone = zoneOf(site.getTimezone());
    IclockSitePolicyService.PolicyView policy = policies.effective(siteId);
    IclockPolicyEngine.Policy rules = rulesFrom(policy);
    Period period = periodFor(label, 1);

    List<IclockPerson> roster = people.findBySiteIdAndActiveTrue(siteId);
    List<IclockPerson> reported =
        roster.stream().filter(p -> !p.isExcludedFromReports()).toList();
    int excluded = roster.size() - reported.size();

    Map<String, String> companyNames = companyNames(reported);

    // One query for the whole month, then bucketed in memory. A query per person per day would be
    // tens of thousands of round trips for a 300-person building.
    Map<String, Map<LocalDate, List<IclockDaySessions.Punch>>> byPersonDay = new LinkedHashMap<>();
    for (IclockPunch p : punches.findBySiteIdAndShiftDateBetweenOrderByEffectiveAtAsc(
        siteId, period.from(), period.to())) {
      if (p.getPersonId() == null || p.getShiftDate() == null) {
        continue;
      }
      byPersonDay
          .computeIfAbsent(p.getPersonId(), k -> new LinkedHashMap<>())
          .computeIfAbsent(p.getShiftDate(), k -> new ArrayList<>())
          .add(new IclockDaySessions.Punch(p.getEffectiveAt(), p.getArea(), p.getDirection()));
    }

    List<PersonReport> rows = new ArrayList<>();
    for (IclockPerson person : reported) {
      IclockShiftProfile profile = policy.profileFor(person.getShiftProfile());
      Map<LocalDate, List<IclockDaySessions.Punch>> theirs =
          byPersonDay.getOrDefault(person.getId(), Map.of());

      List<IclockPolicyEngine.DayFacts> days = new ArrayList<>();
      for (LocalDate d = period.from(); !d.isAfter(period.to()); d = d.plusDays(1)) {
        days.add(
            IclockPolicyEngine.day(
                new IclockPolicyEngine.DayInput(
                    d,
                    theirs.getOrDefault(d, List.of()),
                    profile,
                    person.getLateExemptMin(),
                    false),
                rules,
                zone));
      }

      var f = IclockPolicyEngine.period(days, rules);
      rows.add(new PersonReport(
          person.getId(), person.getPin(), person.getName(),
          companyNameOf(person, companyNames), person.getTeam(), person.getShiftProfile(),
          f.presentDays(), f.absentDays(), f.weeklyOffDays(), f.holidayDays(), f.workingDays(),
          f.totalWorkedMin(), f.totalBreakMin(), f.totalCafeteriaMin(),
          f.allowedBreakMin(), f.excessBreakMin(),
          f.lateDays(), f.totalLateMin(), f.avgLateMin(), f.daysWithMissingPunch(),
          f.expectedWorkMin(), f.productiveMin(), f.unproductiveMin(),
          f.productivePct(), f.workedPct(), f.lopDays(), f.lateDates()));
    }

    // Worst first. A report opened by someone with ten minutes should put the people who need a
    // conversation at the top, not whoever happens to sort first alphabetically.
    rows.sort(java.util.Comparator
        .comparingInt(PersonReport::lopDays).reversed()
        .thenComparing(java.util.Comparator.comparingInt(PersonReport::lateDays).reversed())
        .thenComparing(r -> r.name() == null ? "￿" : r.name().toLowerCase(java.util.Locale.ROOT)));

    return new MonthlyReport(
        siteId, site.getName(), period, rows.size(), excluded, rows, rollup(rows));
  }

  private static List<CompanyRollup> rollup(List<PersonReport> rows) {
    Map<String, CompanyRollup> out = new LinkedHashMap<>();
    for (PersonReport r : rows) {
      String key = r.companyName() == null ? "(no company)" : r.companyName();
      out.merge(
          key,
          new CompanyRollup(key, 1, r.lateDays(), r.lopDays(), r.excessBreakMin()),
          (a, b) -> new CompanyRollup(
              key,
              a.people() + b.people(),
              a.lateDays() + b.lateDays(),
              a.lopDays() + b.lopDays(),
              a.excessBreakMin() + b.excessBreakMin()));
    }
    List<CompanyRollup> list = new ArrayList<>(out.values());
    list.sort(java.util.Comparator.comparingInt(CompanyRollup::lopDays).reversed()
        .thenComparing(CompanyRollup::companyName));
    return list;
  }

  private Map<String, String> companyNames(List<IclockPerson> roster) {
    Set<String> ids = roster.stream()
        .map(IclockPerson::getCompanyId).filter(Objects::nonNull).collect(Collectors.toSet());
    return ids.isEmpty()
        ? Map.of()
        : companies.findAllById(ids).stream()
            .collect(Collectors.toMap(Company::getId, Company::getName));
  }

  /** The resolved company, falling back to the import's verbatim label so nobody is company-less. */
  private static String companyNameOf(IclockPerson p, Map<String, String> names) {
    return p.getCompanyId() == null
        ? p.getCompanyLabel()
        : names.getOrDefault(p.getCompanyId(), p.getCompanyLabel());
  }

  /**
   * The engine's knobs, taken from the building's policy.
   *
   * <p>Shift length comes from the NIGHT profile's own definition rather than a separate number, so a
   * building that moves its shift cannot end up with an expected-hours figure that disagrees with the
   * shift everyone actually works.
   */
  static IclockPolicyEngine.Policy rulesFrom(IclockSitePolicyService.PolicyView policy) {
    double hours = policy.night().length().toMinutes() / 60.0;
    return new IclockPolicyEngine.Policy(
        hours,
        IclockPolicyEngine.Policy.defaults().allowedBreakMin(),
        policy.breakAlertMin() >= 5 ? 5 : policy.breakAlertMin(),
        IclockPolicyEngine.Policy.defaults().lopAfterLateDays());
  }

  private static ZoneId zoneOf(String tz) {
    try {
      return ZoneId.of(tz);
    } catch (Exception e) {
      return com.ihrms.attendance.ShiftConfig.ZONE;
    }
  }
}
