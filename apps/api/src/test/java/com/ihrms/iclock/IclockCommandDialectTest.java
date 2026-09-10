package com.ihrms.iclock;

import static org.assertj.core.api.Assertions.assertThat;

import com.ihrms.attendance.ShiftConfig;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The exact bytes sent to a terminal.
 *
 * <p>These assertions are the closest thing this system has to a contract with hardware. Everything
 * else can be corrected by editing a row; a malformed command is acted on by a device standing in a
 * corridor, and the correction has to travel back through the same channel that just misbehaved.
 */
class IclockCommandDialectTest {

  // ------------------------------------------------------------------ name update

  @Test
  void aNameUpdateCarriesONLYPinNameAndPrivilege() {
    // THE FIELD OMISSION IS THE POINT. On this protocol a present-but-empty field CLEARS it, so a
    // "complete" record with blank Card/Grp/TZ would wipe the card number, group and timezone of
    // every person whose name we corrected — the difference between fixing a display name and
    // locking somebody out of the building.
    String body = IclockCommandDialect.updateUserInfo("18292", "Bipul Mohan");

    assertThat(body).isEqualTo("DATA UPDATE USERINFO PIN=18292\tName=Bipul Mohan\tPri=0");
    assertThat(body).doesNotContain("Card=").doesNotContain("Grp=").doesNotContain("TZ=")
        .doesNotContain("Passwd=");
  }

  @Test
  void fieldsAreTabSeparated() {
    assertThat(IclockCommandDialect.updateUserInfo("1", "A"))
        .isEqualTo("DATA UPDATE USERINFO PIN=1\tName=A\tPri=0");
  }

  // ------------------------------------------------------------------ privilege

  @Test
  void noCommandThisSystemEmitsCanEVERCarryAnElevatedPrivilege() {
    // THE RULING, PINNED. Device-admin rights let somebody standing at a terminal change its clock,
    // its users and its network settings. Granting that is a physical act from the device menu, and
    // this system is deliberately incapable of it — there is no selector, no parameter, and no code
    // path that reaches one. Every command the dialect can produce is checked here, so adding a verb
    // that carries a privilege means changing this test on purpose rather than by accident.
    List<String> everyCommandWeCanBuild = List.of(
        IclockCommandDialect.updateUserInfo("1", "A"),
        IclockCommandDialect.setTime(Instant.EPOCH, ShiftConfig.ZONE),
        IclockCommandDialect.setTimeDeferred(),
        IclockCommandDialect.deleteUser("1"),
        IclockCommandDialect.queryUserInfo(null),
        IclockCommandDialect.queryUserInfo("1"),
        IclockCommandDialect.enrolFinger("1", 0, 3, true),
        IclockCommandDialect.enrolFace("1", 3, true),
        IclockCommandDialect.updateFingerTemplate("1", 6, 1544, 1, "AAAA"),
        IclockCommandDialect.updateBioData("1", 0, 1, "AAAA", 39, 3));

    assertThat(everyCommandWeCanBuild).allSatisfy(body -> {
      // Either the field is absent — leaving the terminal's own value alone — or it is the ordinary
      // user level. Nothing else is reachable.
      Matcher m = Pattern.compile("\\bPri=(\\d+)").matcher(body);
      while (m.find()) {
        assertThat(Integer.parseInt(m.group(1)))
            .as("privilege in: " + body)
            .isEqualTo(IclockCommandDialect.PRIVILEGE_USER);
      }
    });
  }

  @Test
  void aNameUpdateSTATESTheOrdinaryLevelRatherThanLeavingItToTheTerminal() {
    // Sent as a value, not omitted. Omitting would leave whatever the device already held; stating
    // it means an enrolment performed through this system can only ever produce an ordinary user.
    assertThat(IclockCommandDialect.updateUserInfo("18292", "Bipul Mohan"))
        .contains("Pri=" + IclockCommandDialect.PRIVILEGE_USER)
        .doesNotContain("Pri=1").doesNotContain("Pri=2").doesNotContain("Pri=3")
        .doesNotContain("Pri=14");
  }

  // ------------------------------------------------------------------ sanitising

  @Test
  void aTabOrNewlineInANameCannotSplitTheRecord() {
    // Tab is the FIELD separator and newline ends the record, so either one inside a name would hand
    // the device a malformed tail. There is no escape in this protocol, so they are replaced.
    assertThat(IclockCommandDialect.sanitiseName("Anil\tKumar")).isEqualTo("Anil Kumar");
    assertThat(IclockCommandDialect.sanitiseName("Anil\r\nKumar")).isEqualTo("Anil Kumar");
    assertThat(IclockCommandDialect.updateUserInfo("1", "Evil\tName=x"))
        .as("the injected field never becomes its own; Pri stays the last field")
        .isEqualTo("DATA UPDATE USERINFO PIN=1\tName=Evil Name=x\tPri=0");
    assertThat(IclockCommandDialect.updateUserInfo("1", "Evil\tName=x").split("\t"))
        .as("exactly three fields, whatever the name contained")
        .hasSize(3);
  }

  @Test
  void runsOfWhitespaceCollapseAndEndsAreTrimmed() {
    assertThat(IclockCommandDialect.sanitiseName("  R Christy   Savija  "))
        .isEqualTo("R Christy Savija");
  }

  @Test
  void aLongNameIsCutHereRatherThanByTheFirmware() {
    // 24 characters is what these screens render. Truncating deterministically beats letting the
    // device do it in some unspecified way, and beats sending something it may reject outright.
    String long1 = "Kommireddy Chiranjeevi Venkata Sylesh"; // a real roster name, 37 chars
    String cut = IclockCommandDialect.sanitiseName(long1);

    // Cut mid-word, keeping as much as the screen holds. Backing off to the last whole word would
    // drop information for no gain: 24 is a hard device limit, not a typographic preference.
    assertThat(cut).hasSize(24).isEqualTo("Kommireddy Chiranjeevi V");
    assertThat(cut).as("no trailing space left by the cut").isEqualTo(cut.trim());
  }

  @Test
  void aNullNameBecomesEmptyRatherThanTheStringNull() {
    // "null" on a device screen would be worse than the slug it replaced.
    assertThat(IclockCommandDialect.sanitiseName(null)).isEmpty();
  }

  // ------------------------------------------------------------------ time and delete

  @Test
  void theClockIsSetInTheSitesOWNZoneNotUtc() {
    // The device stamps punches with local wall-clock time. An hour out does not fail loudly — it
    // files arrivals into the wrong shift day and surfaces weeks later as an attendance dispute.
    Instant at = LocalDateTime.parse("2026-09-09T19:05:00").atZone(ShiftConfig.ZONE).toInstant();

    assertThat(IclockCommandDialect.setTime(at, ShiftConfig.ZONE))
        .isEqualTo("SET OPTIONS DateTime=2026-09-09 19:05:00");
    assertThat(IclockCommandDialect.setTime(at, java.time.ZoneOffset.UTC))
        .as("the same instant in UTC is a different wall clock — 5h30 out")
        .isEqualTo("SET OPTIONS DateTime=2026-09-09 13:35:00");
  }

  @Test
  void aDeferredClockIsFilledInWhenItIsSERVEDnotWhenItIsQueued() {
    // The payload used to be stamped at queue time, which is only correct if it is served at once.
    // Behind a building-wide name sync — several hundred commands — it would arrive stale and set
    // the terminal that far BEHIND: a plausible-looking wrong clock that files punches into the
    // wrong shift day silently. The first fleet sync escaped this by accident of row ordering.
    String queued = IclockCommandDialect.setTimeDeferred();
    assertThat(queued).isEqualTo("SET OPTIONS DateTime=@SERVER_NOW@");

    Instant served = LocalDateTime.parse("2026-09-09T23:22:52").atZone(ShiftConfig.ZONE).toInstant();
    assertThat(IclockCommandDialect.resolve(queued, served, ShiftConfig.ZONE))
        .isEqualTo("SET OPTIONS DateTime=2026-09-09 23:22:52");
  }

  @Test
  void resolveLeavesEveryOtherPayloadAlone() {
    String name = IclockCommandDialect.updateUserInfo("2004", "Challa Kushal");
    assertThat(IclockCommandDialect.resolve(name, Instant.now(), ShiftConfig.ZONE)).isEqualTo(name);
    assertThat(IclockCommandDialect.resolve(null, Instant.now(), ShiftConfig.ZONE)).isNull();
  }

  @Test
  void deleteNamesOnlyThePin() {
    assertThat(IclockCommandDialect.deleteUser("7777"))
        .isEqualTo("DATA DELETE USERINFO PIN=7777");
  }

  // ------------------------------------------------------------------ enrolment and query

  @Test
  void anEnrolmentNamesTheFingerAndAllowsReplacingIt() {
    // OVERWRITE=1 on purpose: the reason to enrol remotely is almost always that the stored print
    // stopped reading, and a refusal to replace it would be indistinguishable from a dead trigger.
    assertThat(IclockCommandDialect.enrolFinger("19041", 0, 3, true))
        .isEqualTo("ENROLL_FP PIN=19041	FID=0	RETRY=3	OVERWRITE=1");
  }

  @Test
  void aSecondFingerIsAtItsOwnIndexSoTheFirstSurvives() {
    // A person may hold several templates. Enrolling FID 1 must not name FID 0 anywhere in the
    // record, or a spare finger would overwrite the working one.
    String body = IclockCommandDialect.enrolFinger("19041", 1, 3, false);

    assertThat(body).isEqualTo("ENROLL_FP PIN=19041	FID=1	RETRY=3	OVERWRITE=0");
    assertThat(body).doesNotContain("FID=0");
  }

  @Test
  void aUserQueryAsksForEveryoneOrForOnePin() {
    assertThat(IclockCommandDialect.queryUserInfo(null)).isEqualTo("DATA QUERY USERINFO");
    assertThat(IclockCommandDialect.queryUserInfo("")).isEqualTo("DATA QUERY USERINFO");
    assertThat(IclockCommandDialect.queryUserInfo("19041"))
        .isEqualTo("DATA QUERY USERINFO PIN=19041");
  }

  // ------------------------------------------------------------------ template propagation

  @Test
  void aTemplatePushIsTheDevicesOwnRecordHandedBack() {
    // THE EVIDENCE IS THE POINT. This is the exact record ZHM2252000230 POSTed on 2026-09-02, with
    // the inbound verb FP swapped for the outbound DATA UPDATE FINGERTMP and nothing else touched.
    // Every other outbound verb here came from documentation; this one came off the wire.
    String body = IclockCommandDialect.updateFingerTemplate("8003", 6, 1544, 1, "TcdTUzIx");

    assertThat(body)
        .isEqualTo("DATA UPDATE FINGERTMP PIN=8003	FID=6	Size=1544	Valid=1	TMP=TcdTUzIx");
  }

  @Test
  void theReportedSizeIsEchoedNotRecomputedFromTheBase64() {
    // 1544 is the DECODED length the source device reported; the base64 is a different number.
    // Recomputing would be arithmetic on an assumption about padding, and the receiving terminal
    // should get precisely what its sibling said.
    String body = IclockCommandDialect.updateFingerTemplate("8003", 6, 1544, 1, "AAAA");

    assertThat(body).contains("Size=1544").doesNotContain("Size=4").doesNotContain("Size=3");
  }

  @Test
  void faceIsTypeNINEbecauseTheWireSaysSoAndTheSpecificationIsWrong() {
    // The spec documents Type=2. The first DATA QUERY USERINFO on this fleet came back with
    // "BIODATA ... Type=9 MajorVer=36 MinorVer=1" from every terminal that answered. Had the spec
    // value ever been sent, every face command would have addressed a modality this hardware does
    // not have, and failed in a way indistinguishable from the feature being broken.
    assertThat(IclockCommandDialect.TYPE_FACE).isEqualTo(9);
    assertThat(IclockCommandDialect.updateTemplate(
        IclockCommandDialect.TYPE_FACE, "6011", 0, 10, 1, "ZmFjZQ==", 39, 3))
        .startsWith("DATA UPDATE BIODATA")
        .contains("Type=9");
    assertThat(IclockCommandDialect.updateTemplate(
        IclockCommandDialect.TYPE_FINGERPRINT, "6011", 6, 10, 1, "Zmluz2Vy", null, null))
        .startsWith("DATA UPDATE FINGERTMP");
  }

  @Test
  void aFaceTemplateCarriesTheVersionItWasCapturedUnderNeverZero() {
    // 36.1 on the NES cafeteria readers, 39.3 on the ZHM gates. Sending "version 0" would either be
    // rejected or, worse, accepted and stored against a version the terminal cannot match a live
    // face to — a failure with no error, discovered by somebody standing at a door.
    assertThat(IclockCommandDialect.updateBioData("6011", 0, 1, "ZmFjZQ==", 36, 1))
        .contains("MajorVer=36")
        .contains("MinorVer=1")
        .doesNotContain("MajorVer=0");
  }

  @Test
  void aFaceEnrolmentCarriesItsTypeSoTheTerminalKnowsWhatToOpen() {
    // ENROLL_BIO competes with an older ENROLL_FACE spelling and neither has been sent to this
    // fleet yet. A wrong guess comes back as a negative Return on one command rather than silently.
    assertThat(IclockCommandDialect.enrolFace("6011", 3, true))
        .isEqualTo("ENROLL_BIO PIN=6011	TYPE=9	RETRY=3	OVERWRITE=1");
  }

  // ------------------------------------------------------------------ framing and ack

  @Test
  void theServedLineCarriesTheCommandIdTheDeviceWillEchoBack() {
    assertThat(IclockCommandDialect.serve("cmd_abc123", "DATA UPDATE USERINFO PIN=1\tName=A"))
        .isEqualTo("C:cmd_abc123:DATA UPDATE USERINFO PIN=1\tName=A");
  }

  @Test
  void anUnreadableReturnValueIsAFailureNotASuccess() {
    // The alternative is marking a command delivered on the strength of a reply nobody understood.
    // This matters more than usual here: the ack format is UNPROVEN on this fleet.
    assertThat(IclockCommandDialect.isSuccess("0")).isTrue();
    assertThat(IclockCommandDialect.isSuccess("1")).isTrue();
    assertThat(IclockCommandDialect.isSuccess(" 0 ")).isTrue();
    assertThat(IclockCommandDialect.isSuccess("-1")).isFalse();
    assertThat(IclockCommandDialect.isSuccess("-14")).isFalse();
    assertThat(IclockCommandDialect.isSuccess(null)).isFalse();
    assertThat(IclockCommandDialect.isSuccess("")).isFalse();
    assertThat(IclockCommandDialect.isSuccess("OK")).as("unparseable is not success").isFalse();
  }

  // ------------------------------------------------------------------ ack parsing

  @Test
  void ackFieldsAreReadFromEitherCarrier() {
    // Observed since: both platforms POST `ID=<id>&Return=0&CMD=DATA`. The newline-joined form is
    // kept anyway — 752 acks from two firmwares is not every firmware, and the cost of reading a
    // shape we never meet is nothing next to silently dropping every acknowledgement.
    assertThat(IclockController.formValue("ID=cmd_1&Return=0&CMD=DATA", "ID")).isEqualTo("cmd_1");
    assertThat(IclockController.formValue("ID=cmd_1&Return=0&CMD=DATA", "Return")).isEqualTo("0");
    assertThat(IclockController.formValue("ID=cmd_2\r\nReturn=-14\r\nCMD=DATA", "Return"))
        .isEqualTo("-14");
    assertThat(IclockController.formValue("id=cmd_3&return=0", "ID"))
        .as("firmware casing varies")
        .isEqualTo("cmd_3");
    assertThat(IclockController.formValue("", "ID")).isNull();
    assertThat(IclockController.formValue(null, "ID")).isNull();
  }
}
