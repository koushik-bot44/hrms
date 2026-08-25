package com.ihrms.iclock;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The handshake options block is what configures the terminal. Under P0 it was never actually
 * delivered — the firmware calls {@code cdata.aspx}, which fell to the catch-all — so the device sat
 * in batch mode. No Spring context needed, so this runs without a local Postgres.
 */
class IclockOptionsTest {

  private static final IclockProperties.Options OPTIONS = IclockProperties.Options.defaults();

  @Test
  void enablesRealtimePushWhichIsThePointOfTheHandshake() {
    String block = IclockOptions.block("ABC123", "100", "200", OPTIONS);

    // Without Realtime=1 the device only uploads at its scheduled TransTimes slots.
    assertThat(block).contains("Realtime=1");
  }

  @Test
  void echoesTheSerialInTheHeaderLineTheFirmwareExpects() {
    String block = IclockOptions.block("ABC123", "100", "200", OPTIONS);

    assertThat(block).startsWith("GET OPTION FROM: ABC123\r\n");
  }

  @Test
  void usesCrlfLineEndings() {
    String block = IclockOptions.block("ABC123", "100", "200", OPTIONS);

    assertThat(block).endsWith("\r\n");
    assertThat(block.replace("\r\n", "")).doesNotContain("\n");
  }

  @Test
  void servesEveryRegistryStampSoTheDeviceResumesRatherThanReUploading() {
    String block = IclockOptions.block("ABC123", "100", "200", OPTIONS);

    // ATTLOG uses the Stamp cursor; OPERLOG/BIODATA/ATTPHOTO use OpStamp.
    assertThat(block)
        .contains("Stamp=100")
        .contains("ATTLOGStamp=100")
        .contains("OpStamp=200")
        .contains("OPERLOGStamp=200")
        .contains("BIODATAStamp=200")
        .contains("ATTPHOTOStamp=200");
  }

  @Test
  void carriesTheTuningKeysThisPushverExpects() {
    String block = IclockOptions.block("ABC123", "1", "1", OPTIONS);

    assertThat(block)
        .contains("ErrorDelay=30")
        .contains("Delay=30")
        .contains("TransTimes=00:00;14:05")
        .contains("TransInterval=1")
        .contains("TransFlag=1111000000")
        .contains("TimeZone=330") // +05:30 expressed in MINUTES — see the format guard below
        .contains("Encrypt=0")
        .contains("ServerVer=2.4.1");
  }

  @Test
  void timeZoneIsAnIntegerMinuteOffsetNeverFractionalHours() {
    // REGRESSION GUARD. A default of "5.5" (hours) shipped once and the firmware silently truncated
    // it to 5, putting every terminal that handshook 30 minutes SLOW and mis-stamping every punch
    // until it was re-handshaken and 76 rows were repaired. The wire format must stay an integer
    // number of minutes; anything with a decimal point is the exact shape of that outage.
    String timeZone =
        IclockOptions.block("ABC123", "1", "1", OPTIONS)
            .lines()
            .filter(l -> l.startsWith("TimeZone="))
            .findFirst()
            .orElseThrow()
            .substring("TimeZone=".length());

    assertThat(timeZone).matches("-?\\d+").doesNotContain(".");
    assertThat(Integer.parseInt(timeZone)).isEqualTo(330); // +05:30
  }

  @Test
  void theDefaultOptionsCarryAnIntegerMinuteTimeZone() {
    // Guards the bound default itself, not just what the block renders.
    assertThat(IclockProperties.Options.defaults().timeZone()).matches("-?\\d+").isEqualTo("330");
  }

  @Test
  void fallsBackToTheStampThisFirmwareItselfSendsWhenNoneIsKnownYet() {
    // A device that has never pushed has no stored cursor; echoing its own idiom (9999) is safer
    // than inventing a number it has never seen.
    String block = IclockOptions.block("ABC123", null, null, OPTIONS);

    assertThat(block)
        .contains("Stamp=" + IclockOptions.DEFAULT_STAMP)
        .contains("OpStamp=" + IclockOptions.DEFAULT_STAMP)
        .doesNotContain("null");
  }

  @Test
  void omitsServerVerWhenBlankRatherThanEmittingAnEmptyKey() {
    IclockProperties.Options noVer =
        new IclockProperties.Options(30, 30, "00:00", 1, "1111000000", "5.5", 1, 0, "");

    assertThat(IclockOptions.block("ABC123", "1", "1", noVer)).doesNotContain("ServerVer");
  }

  @Test
  void survivesAMissingSerialRatherThanPrintingNull() {
    String block = IclockOptions.block(null, "1", "1", OPTIONS);

    assertThat(block).startsWith("GET OPTION FROM: \r\n").doesNotContain("null");
  }
}
