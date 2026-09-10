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
   * The ONLY privilege level this system is capable of emitting: an ordinary user.
   *
   * <p>Higher values on this protocol are enroller, manager and administrator — a device admin can
   * stand at the terminal and change its clock, its users and its network settings. Granting that is
   * a physical act performed from the device menu by somebody who is already there, and it is
   * deliberately outside this system's reach: a console with a privilege dropdown is a console where
   * a mis-click makes somebody an administrator of the door.
   */
  static final int PRIVILEGE_USER = 0;

  /**
   * Body of a name update for one enrolled user.
   *
   * <p><b>PIN, Name and Pri — and nothing else.</b> Passwd, Card, Grp and TZ are DELIBERATELY
   * OMITTED rather than sent blank: on this protocol a present-but-empty field is an instruction to
   * clear it, so a "complete" record would wipe the card number, group and timezone of every person
   * whose name we corrected. That is the difference between fixing a display name and locking
   * somebody out of the building.
   *
   * <p>{@code Pri} is the exception, and it is sent as a VALUE rather than omitted, which is a
   * different decision from the one above. Omitting it would leave whatever the terminal already
   * held; sending {@code Pri=0} states it. The rule is that no command this system emits may ever
   * carry an elevated privilege — see {@link #PRIVILEGE_USER} — and stating it beats trusting it.
   *
   * <p><b>The cost, said plainly:</b> pushing a name to somebody who was made a device admin at the
   * terminal demotes them back to an ordinary user. That is the intended direction. Device-admin
   * rights are granted from the device menu and this system does not track them, so it cannot
   * preserve what it cannot see — and quietly preserving an elevation nobody here recorded is the
   * worse of the two failures.
   */
  static String updateUserInfo(String pin, String name) {
    return "DATA UPDATE USERINFO PIN=" + pin
        + TAB + "Name=" + sanitiseName(name)
        + TAB + "Pri=" + PRIVILEGE_USER;
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
   * Body of a remote fingerprint enrolment — the command that puts a terminal into capture mode.
   *
   * <p><b>UNPROVEN ON THIS FLEET.</b> Everything above has been observed acking; this verb has not
   * been sent to a device here. It is the documented pushver trigger, and it is the one command in
   * this class whose success cannot be established from an ack alone: a terminal can answer
   * {@code Return=0} meaning "understood" and then do nothing visible, because the outcome depends
   * on a person standing at it. See {@code IclockCommandService.queueEnrolment} for what counts as
   * evidence.
   *
   * <p>Fields, all of which this firmware family expects present:
   *
   * <ul>
   *   <li>{@code FID} — which finger, 0-9. A person may hold several templates; enrolling FID 1
   *       leaves FID 0 alone, which is how a second finger is added without risking the working one.
   *   <li>{@code RETRY} — how many scans the device asks for before it accepts a template. Three is
   *       the firmware default and what the on-device menu uses.
   *   <li>{@code OVERWRITE} — whether an existing template at that FID may be replaced. Sent as 1:
   *       the reason to enrol remotely is almost always that the stored print stopped reading, and
   *       an enrolment that silently refuses to replace it would look like the trigger failed.
   * </ul>
   */
  static String enrolFinger(String pin, int fingerIndex, int retries, boolean overwrite) {
    return "ENROLL_FP PIN=" + pin
        + TAB + "FID=" + fingerIndex
        + TAB + "RETRY=" + retries
        + TAB + "OVERWRITE=" + (overwrite ? 1 : 0);
  }

  /**
   * Body of a fingerprint push - one template onto one terminal.
   *
   * <p><b>This shape is not a guess; it is the fleet's own.</b> Every other outbound verb here was
   * taken from documentation and confirmed by an ack. This one is the exact record these terminals
   * already POST to us when somebody enrols, with the verb changed from the inbound {@code FP} to
   * the outbound {@code DATA UPDATE FINGERTMP}. Sending a device back its own serialisation of a
   * template is the strongest evidence available short of trying it:
   *
   * <pre>
   *   received:  FP PIN=8003\tFID=6\tSize=1544\tValid=1\tTMP=TcdTUzIx...
   *   sent:      DATA UPDATE FINGERTMP PIN=8003\tFID=6\tSize=1544\tValid=1\tTMP=TcdTUzIx...
   * </pre>
   *
   * <p><b>Size and Valid are echoed, not recomputed.</b> Size is the DECODED byte count the source
   * device reported. Recomputing it from the base64 would be arithmetic on an assumption about
   * padding; echoing it hands the receiving terminal precisely what its sibling said, and if the two
   * ever disagree that is a fact worth seeing rather than one to paper over.
   *
   * <p><b>Portability across firmware families is UNPROVEN here.</b> All five templates this fleet
   * has produced came from ZAM180 gates; no ZAM230 cafeteria terminal has ever sent one, so whether
   * a ZAM180 template is accepted by a ZAM230 is a question only the wire can answer. The command is
   * built the same either way and the failure, if it comes, arrives as a negative {@code Return} on
   * a single command rather than as silent corruption.
   */
  static String updateFingerTemplate(String pin, int fid, int size, int valid, String template) {
    return "DATA UPDATE FINGERTMP PIN=" + pin
        + TAB + "FID=" + fid
        + TAB + "Size=" + size
        + TAB + "Valid=" + valid
        + TAB + "TMP=" + template;
  }

  /** Biometric type numbering, as the devices themselves use it. */
  static final int TYPE_FINGERPRINT = 1;

  static final int TYPE_FACE = 2;

  /**
   * Body of a remote FACE enrolment.
   *
   * <p><b>Beta, and honestly so.</b> The fingerprint trigger has a single documented spelling that
   * this firmware family has used for years. Face does not: the newer push specs carry it as
   * {@code ENROLL_BIO} with a {@code TYPE}, while some builds answer to {@code ENROLL_FACE}. This
   * fleet has produced no evidence either way — no face template has ever reached the server, and no
   * BIODATA push has ever arrived at all.
   *
   * <p>{@code ENROLL_BIO} is sent because it is the form the current specification documents and it
   * generalises to palm and vein if those ever matter. If this firmware wants the other spelling the
   * command comes back with a negative {@code Return} rather than failing silently, and the command
   * log will say so — which is the whole reason to try it on one terminal before offering it as a
   * finished feature.
   */
  static String enrolFace(String pin, int retries, boolean overwrite) {
    return "ENROLL_BIO PIN=" + pin
        + TAB + "TYPE=" + TYPE_FACE
        + TAB + "RETRY=" + retries
        + TAB + "OVERWRITE=" + (overwrite ? 1 : 0);
  }

  /**
   * Body of a face-template push.
   *
   * <p>The face counterpart to {@link #updateFingerTemplate}, and weaker evidence than that one by a
   * wide margin: the fingerprint form is the fleet's own serialisation handed back, while this shape
   * comes from the specification alone. No face template has ever been seen here to copy.
   */
  static String updateBioData(String pin, int fid, int size, int valid, String template) {
    return "DATA UPDATE BIODATA PIN=" + pin
        + TAB + "No=" + fid
        + TAB + "Index=0"
        + TAB + "Valid=" + valid
        + TAB + "Duress=0"
        + TAB + "Type=" + TYPE_FACE
        + TAB + "MajorVer=0"
        + TAB + "MinorVer=0"
        + TAB + "Format=0"
        + TAB + "Tmp=" + template;
  }

  /** The right push for a stored template, by its type. */
  static String updateTemplate(int bioType, String pin, int fid, int size, int valid, String tmp) {
    return bioType == TYPE_FACE
        ? updateBioData(pin, fid, size, valid, tmp)
        : updateFingerTemplate(pin, fid, size, valid, tmp);
  }

  /**
   * Body of a request for a terminal's own user table.
   *
   * <p><b>This is the missing input.</b> The USERINFO parser was written for a push no device on
   * this fleet has ever volunteered — 129,747 requests, zero {@code Name=}. Rather than wait for one,
   * ask: the device answers a QUERY by POSTing {@code table=USERINFO} to {@code cdata}, which lands
   * in the ingest path already built for it. It is a read on the device's side and changes nothing
   * there, which makes it the safest of the new verbs to try first.
   *
   * <p>With no pin it asks for every user, which on a 250-person terminal is a large body arriving
   * in one push; a pin narrows it to one.
   */
  static String queryUserInfo(String pin) {
    return pin == null || pin.isBlank()
        ? "DATA QUERY USERINFO"
        : "DATA QUERY USERINFO PIN=" + pin;
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
