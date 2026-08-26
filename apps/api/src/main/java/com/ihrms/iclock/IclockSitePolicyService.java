package com.ihrms.iclock;

import com.ihrms.domain.model.IclockSitePolicy;
import com.ihrms.domain.repository.IclockSitePolicyRepository;
import com.ihrms.domain.repository.IclockSiteRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Per-building policy settings — read by the board on every refresh, written by the console.
 *
 * <p>These were compile-time constants, which meant tuning the break threshold against a real floor
 * required a redeploy. They are numbers an operator adjusts after watching a week of shifts, so they
 * belong in the database and in the UI.
 */
@Service
public class IclockSitePolicyService {

  /** Used when a building has no policy row yet. Resolution is total without one, by design. */
  public static final int DEFAULT_BREAK_ALERT_MIN = 30;
  public static final int DEFAULT_BREAK_ALERT_MAX_MIN = 120;

  private final IclockSitePolicyRepository policies;
  private final IclockSiteRepository sites;

  public IclockSitePolicyService(
      IclockSitePolicyRepository policies, IclockSiteRepository sites) {
    this.policies = policies;
    this.sites = sites;
  }

  /**
   * What the console shows and the board applies.
   *
   * <p>Carries the shift PROFILES as well as the alert thresholds, because everything downstream of a
   * punch — which shift-day it files under, whether it is late, whether an absence is a break — is a
   * question about the person's shift, and the person's shift is defined here.
   */
  public record PolicyView(
      String siteId,
      int breakAlertMin,
      int breakAlertMaxMin,
      boolean stored,
      IclockShiftProfile night,
      IclockShiftProfile day) {

    /**
     * The profile a person is assigned to, falling back to NIGHT for anything unrecognised.
     *
     * <p>Falling back rather than throwing is deliberate: an unknown profile name must never re-date
     * somebody. NIGHT is current behaviour, so the failure mode of a bad value is "unchanged", not
     * "silently attributed to a shift they do not work".
     */
    public IclockShiftProfile profileFor(String name) {
      return "DAY".equals(name) ? day : night;
    }
  }

  /**
   * The effective policy for a building — the stored row, or the defaults when there is none.
   *
   * <p>Never throws for a missing row. Tests create sites straight through the repository, bypassing
   * {@code createSite}, so a resolver that demanded a row would fail in every DB-gated test that makes
   * a site; and on the live path a building created before V46 must keep working rather than 500.
   */
  @Transactional(readOnly = true)
  public PolicyView effective(String siteId) {
    return policies
        .findBySiteId(siteId)
        .map(p -> viewOf(siteId, p))
        .orElseGet(
            () ->
                new PolicyView(
                    siteId,
                    DEFAULT_BREAK_ALERT_MIN,
                    DEFAULT_BREAK_ALERT_MAX_MIN,
                    false,
                    IclockShiftProfile.NIGHT,
                    IclockShiftProfile.DAY));
  }

  /**
   * One place that turns a stored row into a view, so {@code effective} and {@code save} cannot
   * disagree about what a saved policy means — the console reads the second and the board reads the
   * first, and a divergence would show as a setting that "did not take" until the next refresh.
   */
  private static PolicyView viewOf(String siteId, IclockSitePolicy p) {
    return new PolicyView(
        siteId,
        p.getBreakAlertMin(),
        p.getBreakAlertMaxMin(),
        true,
        new IclockShiftProfile("NIGHT", p.getNightStart(), p.getNightEnd(), p.getNightLateGraceMin()),
        new IclockShiftProfile("DAY", p.getDayStart(), p.getDayEnd(), p.getDayLateGraceMin()));
  }

  /**
   * Saves the thresholds for one building, creating the row if this is the first time.
   *
   * <p>The ordering rule is enforced here as well as in the database, so the caller gets a 400 with a
   * readable sentence instead of the 500 a bare CHECK violation produces — Hibernate's
   * {@code ddl-auto: validate} checks tables, columns and types, never CHECK constraints, so a
   * constraint alone is not a contract the API can honour politely.
   */
  @Transactional
  public PolicyView save(String siteId, int breakAlertMin, int breakAlertMaxMin) {
    if (sites.findById(siteId).isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Building not found");
    }
    if (breakAlertMin < 5) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Alert after must be at least 5 minutes — a shorter gap is not a break.");
    }
    if (breakAlertMaxMin <= breakAlertMin) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "\"Stop treating as break after\" must be greater than \"Alert after\", otherwise every alert "
              + "retires the instant it fires.");
    }
    IclockSitePolicy row =
        policies
            .findBySiteId(siteId)
            .orElseGet(
                () -> {
                  IclockSitePolicy fresh = new IclockSitePolicy();
                  fresh.setSiteId(siteId);
                  return fresh;
                });
    row.setBreakAlertMin(breakAlertMin);
    row.setBreakAlertMaxMin(breakAlertMaxMin);
    IclockSitePolicy saved = policies.save(row);
    return viewOf(siteId, saved);
  }
}
