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
 * <p><b>THIS IS UNPROVEN ON THIS FLEET.</b> No terminal here has ever been sent a command — the
 * request log holds zero {@code devicecmd} rows — so the ack half of this contract is documented
 * behaviour, not observed behaviour. The canary exists to turn one into the other, and the raw
 * capture filter records whatever actually arrives even if it disagrees with everything below.
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
