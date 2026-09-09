package com.ihrms.iclock;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * The wire format for device commands — as a pure function.
 *
 * <p>Pure and Spring-free for the same reason the grouping and shift rules are: the exact bytes are
 * the whole behaviour, and they should be assertable without a database, a device, or a deploy. This
 * is also the one place in the system that composes a string a terminal will ACT on, so it is worth
 * being able to see all of it at once.
 *
 * <p><b>The shape.</b> ADMS pushver 2.4.1 serves commands from {@code getrequest} as:
 *
 * <pre>
 *   C:&lt;id&gt;:DATA UPDATE USERINFO PIN=123\tName=Anil Kumar\tPri=0\tPasswd=\tCard=\tGrp=1\tTZ=0000000000000000
 * </pre>
 *
 * <p>Fields are TAB separated; the command word and its arguments are space separated. The
 * {@code C:<id>:} prefix is what the device echoes back when acknowledging, which is how a reply is
 * matched to the row that caused it.
 *
 * <p><b>PROVEN ON BOTH PLATFORMS, 2026-09-09.</b> This was documented-not-observed until the first
 * command was ever sent to this fleet. Both canaries acked in the documented shape, carried as a POST
 * body to {@code /iclock/devicecmd.aspx}:
 *
 * <pre>
 *   ZAM180  ZHM2252000230  ID=cmd_cfabcc060854b4933&amp;Return=0&amp;CMD=DATA   496 ms
 *   ZAM230  NES1255300684  ID=cmd_eda0cff24b62a6a43&amp;Return=0&amp;CMD=DATA   451 ms
 * </pre>
 *
 * <p>A 752-command fleet sync followed, every one {@code Return=0}, no failures. Devices re-poll
 * immediately after acknowledging rather than waiting out their ~30s idle interval, so a queue of
 * several hundred drains in minutes rather than the hour the idle rate suggests.
 */
final class IclockCommandDialect {

  private IclockCommandDialect() {}

  /** Field separator inside a DATA record. */
  private static final char TAB = '\t';

  /** {@code yyyy-MM-dd HH:mm:ss}, the same wall-clock shape the terminals use for punches. */
  private static final DateTimeFormatter DEVICE_TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  /**
   * A name safe to put on the wire.
   *
   * <p>Tabs and newlines are the record and field separators, so a name containing either would
   * split one command into two and hand the device a malformed tail. Stripped rather than escaped:
   * the protocol has no escape, and a silently truncated name is better than a corrupted record.
   *
   * <p>Trimmed to 24 characters, which is the practical limit these screens render. A longer name is
   * cut here, visibly and deterministically, rather than by the firmware in some unspecified way.
   */
  static String sanitiseName(String raw) {
    if (raw == null) {
      return "";
    }
    String clean = raw.replaceAll("[\\t\\r\\n]", " ").replaceAll("\\s+", " ").trim();
    return clean.length() <= 24 ? clean : clean.substring(0, 24).trim();
  }

  /**
   * Body of a name update for one enrolled user.
   *
   * <p>Only PIN and Name are set. Every other field the record can carry — Pri, Passwd, Card, Grp,
   * TZ — is DELIBERATELY OMITTED rather than sent blank: on this protocol a present-but-empty field
   * is an instruction to clear it, so sending the full record would wipe the card number, group and
   * timezone of every person whose name we corrected. That is the difference between fixing a
   * display name and locking somebody out of the building.
   */
  static String updateUserInfo(String pin, String name) {
    return "DATA UPDATE USERINFO PIN=" + pin + TAB + "Name=" + sanitiseName(name);
  }

  /**
   * Body of a clock set.
   *
   * <p>The device keeps local wall-clock time and stamps punches with it, so this is sent in the
   * SITE's zone, not UTC. A terminal an hour out does not fail loudly; it files arrivals into the
   * wrong shift day, which surfaces weeks later as an attendance dispute.
   */
  static String setTime(Instant at, ZoneId zone) {
    return "SET OPTIONS DateTime=" + DEVICE_TIME.format(at.atZone(zone));
  }

  /**
   * Placeholder stored in a SET_TIME payload, substituted with the real clock AT SERVE TIME.
   *
   * <p><b>Because a queued timestamp goes stale.</b> The payload used to be built when the command
   * was queued, which is correct only if it is served immediately. Behind a long queue — a
   * building-wide name sync is several hundred commands — it would arrive minutes or hours old and
   * set the terminal that far BEHIND, which is worse than not setting it at all: a device with a
   * plausible-looking wrong clock files punches into the wrong shift day silently.
   *
   * <p>The first fleet sync got away with it by luck. Postgres {@code now()} is transaction-start
   * time, so every row in that batch shared a {@code createdAt}, the time commands sorted first and
   * were served within 28 seconds. Luck is not a mechanism.
   */
  static final String NOW_PLACEHOLDER = "@SERVER_NOW@";

  /** A SET_TIME body whose clock is filled in when the device actually asks for it. */
  static String setTimeDeferred() {
    return "SET OPTIONS DateTime=" + NOW_PLACEHOLDER;
  }

  /** Substitutes the real clock into a deferred payload. Any other payload passes through. */
  static String resolve(String payload, Instant at, ZoneId zone) {
    if (payload == null || !payload.contains(NOW_PLACEHOLDER)) {
      return payload;
    }
    return payload.replace(NOW_PLACEHOLDER, DEVICE_TIME.format(at.atZone(zone)));
  }

  /**
   * Body of a user deletion.
   *
   * <p>Removes the enrolment — fingerprint templates and all — from the terminal. Irreversible from
   * here: the person has to physically re-enrol. The guard for who may be deleted lives in the
   * service, not in this string.
   */
  static String deleteUser(String pin) {
    return "DATA DELETE USERINFO PIN=" + pin;
  }

  /** The full line served to a device: the id prefix plus the stored body. */
  static String serve(String commandId, String payload) {
    return "C:" + commandId + ":" + payload;
  }

  /**
   * The {@code Return=} value a device sends back, read as success or failure.
   *
   * <p>Zero and positive values are success on this protocol; negative values are errors. Anything
   * unparseable is treated as a FAILURE rather than a success, because the alternative is marking a
   * command delivered on the strength of a reply nobody understood.
   */
  static boolean isSuccess(String returnValue) {
    if (returnValue == null || returnValue.isBlank()) {
      return false;
    }
    try {
      return Long.parseLong(returnValue.trim()) >= 0;
    } catch (NumberFormatException e) {
      return false;
    }
  }
}
