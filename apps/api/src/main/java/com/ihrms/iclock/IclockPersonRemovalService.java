package com.ihrms.iclock;

import com.ihrms.domain.model.IclockDevice;
import com.ihrms.domain.model.IclockPerson;
import com.ihrms.domain.model.IclockPunch;
import com.ihrms.domain.repository.IclockDeviceRepository;
import com.ihrms.domain.repository.IclockPersonRepository;
import com.ihrms.domain.repository.IclockPunchRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Taking somebody off the system — the terminals AND the roster, in one decision.
 *
 * <p><b>Why this is one flow rather than two buttons.</b> Removing a person has always needed both
 * halves, and doing them separately is how the register and the roster drift apart: deactivate
 * without clearing the terminals and their face still opens the door for months; clear the terminals
 * without touching the roster and they stay in every report as a no-show. Both halves, one confirm,
 * one audit entry.
 *
 * <p><b>PUNCHES ARE NEVER TOUCHED.</b> Not by this path, not by any path here. The history of
 * somebody having worked here is not the thing that is wrong, and deleting it would destroy the
 * evidence explaining attendance already recorded and paid against.
 */
@Service
public class IclockPersonRemovalService {

  private static final Logger log = LoggerFactory.getLogger(IclockPersonRemovalService.class);

  /**
   * A punch this recent means the person is almost certainly still here.
   *
   * <p>Flagged rather than blocked. There are real reasons to remove somebody who badged yesterday —
   * they left today — but in a bulk selection that is exactly where a mis-click hides, and an
   * operator who sees "last punched 2 hours ago" beside a name will stop.
   */
  private static final int RECENT_DAYS = 7;

  private final IclockPersonRepository people;
  private final IclockDeviceRepository devices;
  private final IclockPunchRepository punches;
  private final IclockRosterService roster;
  private final IclockCommandService commands;

  public IclockPersonRemovalService(
      IclockPersonRepository people,
      IclockDeviceRepository devices,
      IclockPunchRepository punches,
      IclockRosterService roster,
      IclockCommandService commands) {
    this.people = people;
    this.devices = devices;
    this.punches = punches;
    this.roster = roster;
    this.commands = commands;
  }

  /** What removing one person would do, or did. */
  public record RemovalRow(
      String personId,
      String pin,
      String name,
      /** DELETE when the roster row can go entirely; DEACTIVATE when history holds it. */
      String rosterOutcome,
      String rosterReason,
      int terminals,
      long punchCount,
      Instant lastPunchAt,
      /** Punched within the last week — the "are you sure" flag. */
      boolean recentlyActive) {}

  public record RemovalReport(
      boolean committed,
      int people,
      int toDelete,
      int toDeactivate,
      int commandsQueued,
      int recentlyActive,
      List<RemovalRow> rows) {}

  /**
   * What removal WOULD do. Queues nothing, writes nothing.
   *
   * <p>A separate entry point rather than a flag, per the standing rule, and {@code readOnly = true}
   * puts Hibernate in FlushMode.MANUAL so a stray mutation cannot reach the database.
   */
  @Transactional(readOnly = true)
  public RemovalReport preview(String siteId, List<String> personIds) {
    return decide(siteId, personIds, false, null);
  }

  /** Does it. Roster first, then the terminals — see {@link #decide} for why that order. */
  @Transactional
  public RemovalReport remove(String siteId, List<String> personIds, String actorId) {
    return decide(siteId, personIds, true, actorId);
  }

  private RemovalReport decide(
      String siteId, List<String> personIds, boolean commit, String actorId) {
    List<IclockDevice> terminals = devices.findBySiteId(siteId).stream()
        .filter(d -> "CLAIMED".equals(d.getStatus()))
        .toList();

    List<RemovalRow> rows = new ArrayList<>();
    int toDelete = 0, toDeactivate = 0, queued = 0, recent = 0;

    for (String personId : personIds) {
      IclockPerson person = people.findById(personId).orElse(null);
      if (person == null || !siteId.equals(person.getSiteId())) {
        // BUILDING-BOUNDED. A person id from another building is not a removal this screen may
        // perform, whatever was posted — the same wall the command funnel enforces.
        continue;
      }
      var preflight = roster.deletePreflight(personId);
      Instant last = punches.findFirstByPersonIdOrderByPunchedAtDesc(personId)
          .map(IclockPunch::getPunchedAt)
          .orElse(null);
      boolean recentlyActive =
          last != null && last.isAfter(Instant.now().minus(RECENT_DAYS, ChronoUnit.DAYS));
      if (recentlyActive) {
        recent++;
      }
      if (preflight.allowed()) {
        toDelete++;
      } else {
        toDeactivate++;
      }

      if (commit) {
        // ROSTER FIRST, TERMINALS SECOND, and the order is load-bearing. The command service refuses
        // to delete a pin belonging to somebody ACTIVE at that building — which is the right guard
        // and would block this whole flow if the terminals went first.
        if (preflight.allowed()) {
          roster.deletePerson(personId);
        } else {
          person.setActive(false);
          people.save(person);
        }
        for (IclockDevice d : terminals) {
          // Every claimed terminal in the building, not only the ones we believe hold them. Our
          // record of who is enrolled where comes from register audits, and most terminals have
          // never been audited; a delete for a pin a device does not have is a no-op, while a
          // terminal skipped on a stale belief keeps the enrolment forever.
          commands.queueUserDelete(d.getId(), person.getPin(), actorId);
          queued++;
        }
      } else {
        queued += terminals.size();
      }

      rows.add(new RemovalRow(
          personId, person.getPin(), person.getName(),
          preflight.allowed() ? "DELETE" : "DEACTIVATE",
          preflight.reason(),
          terminals.size(),
          preflight.punchCount(),
          last,
          recentlyActive));
    }

    if (commit) {
      log.info("iclock: removed {} person(s) at site {} — {} deleted, {} deactivated, {} device "
          + "deletion(s) queued", rows.size(), siteId, toDelete, toDeactivate, queued);
    }
    return new RemovalReport(
        commit, rows.size(), toDelete, toDeactivate, queued, recent, rows);
  }

  /**
   * Roster-only removal, for people the terminals hold nothing for.
   *
   * <p>The audit screen's template-gap list uses this: those people are on the roster and enrolled
   * nowhere, so there is no register to clear and queueing six deletions for a pin no terminal has
   * would be noise in every command log.
   */
  @Transactional
  public RemovalReport removeFromRosterOnly(String siteId, List<String> personIds, String actorId) {
    List<RemovalRow> rows = new ArrayList<>();
    int toDelete = 0, toDeactivate = 0, recent = 0;
    for (String personId : personIds) {
      IclockPerson person = people.findById(personId).orElse(null);
      if (person == null || !siteId.equals(person.getSiteId())) {
        continue;
      }
      var preflight = roster.deletePreflight(personId);
      Instant last = punches.findFirstByPersonIdOrderByPunchedAtDesc(personId)
          .map(IclockPunch::getPunchedAt)
          .orElse(null);
      boolean recentlyActive =
          last != null && last.isAfter(Instant.now().minus(RECENT_DAYS, ChronoUnit.DAYS));
      if (recentlyActive) {
        recent++;
      }
      if (preflight.allowed()) {
        roster.deletePerson(personId);
        toDelete++;
      } else {
        person.setActive(false);
        people.save(person);
        toDeactivate++;
      }
      rows.add(new RemovalRow(
          personId, person.getPin(), person.getName(),
          preflight.allowed() ? "DELETE" : "DEACTIVATE",
          preflight.reason(), 0, preflight.punchCount(), last, recentlyActive));
    }
    log.info("iclock: roster-only removal of {} person(s) at site {}", rows.size(), siteId);
    return new RemovalReport(true, rows.size(), toDelete, toDeactivate, 0, recent, rows);
  }

  /** One person, both halves. The row menu's "Remove person…". */
  @Transactional
  public RemovalReport removeOne(String personId, String actorId) {
    IclockPerson person = people.findById(personId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Person not found"));
    return remove(person.getSiteId(), List.of(personId), actorId);
  }

  /** What removing one person would do. */
  @Transactional(readOnly = true)
  public RemovalReport previewOne(String personId) {
    IclockPerson person = people.findById(personId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Person not found"));
    return preview(person.getSiteId(), List.of(personId));
  }
}
