package com.ihrms.iclock;

import static org.assertj.core.api.Assertions.assertThat;

import com.ihrms.attendance.ShiftConfig;
import java.time.Instant;
import java.time.LocalDateTime;
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
  void aNameUpdateCarriesONLYPinAndName() {
    // THE FIELD OMISSION IS THE POINT. On this protocol a present-but-empty field CLEARS it, so a
    // "complete" record with blank Card/Grp/TZ would wipe the card number, group and timezone of
    // every person whose name we corrected — the difference between fixing a display name and
    // locking somebody out of the building.
    String body = IclockCommandDialect.updateUserInfo("18292", "Bipul Mohan");

    assertThat(body).isEqualTo("DATA UPDATE USERINFO PIN=18292\tName=Bipul Mohan");
    assertThat(body).doesNotContain("Card=").doesNotContain("Grp=").doesNotContain("TZ=")
        .doesNotContain("Passwd=").doesNotContain("Pri=");
  }

  @Test
  void fieldsAreTabSeparated() {
    assertThat(IclockCommandDialect.updateUserInfo("1", "A"))
        .containsOnlyOnce("\t")
        .isEqualTo("DATA UPDATE USERINFO PIN=1\tName=A");
  }

  // ------------------------------------------------------------------ sanitising

  @Test
  void aTabOrNewlineInANameCannotSplitTheRecord() {
    // Tab is the FIELD separator and newline ends the record, so either one inside a name would hand
    // the device a malformed tail. There is no escape in this protocol, so they are replaced.
    assertThat(IclockCommandDialect.sanitiseName("Anil\tKumar")).isEqualTo("Anil Kumar");
    assertThat(IclockCommandDialect.sanitiseName("Anil\r\nKumar")).isEqualTo("Anil Kumar");
    assertThat(IclockCommandDialect.updateUserInfo("1", "Evil\tName=x"))
        .as("one tab in, one tab out — the injected field never becomes its own")
        .isEqualTo("DATA UPDATE USERINFO PIN=1\tName=Evil Name=x");
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
  void deleteNamesOnlyThePin() {
    assertThat(IclockCommandDialect.deleteUser("7777"))
        .isEqualTo("DATA DELETE USERINFO PIN=7777");
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
    // The shape is documented, not observed — no terminal here has ever called devicecmd — so both
    // an &-joined body and a newline-joined one are read rather than guessing which this firmware
    // sends and silently dropping every acknowledgement.
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
