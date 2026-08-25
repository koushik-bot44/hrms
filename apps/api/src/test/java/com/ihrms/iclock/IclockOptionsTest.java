package com.ihrms.iclock;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The handshake options block is what configures the terminal. No Spring context needed, so this
 * runs without a local Postgres.
 */
class IclockOptionsTest {

  private static final IclockProperties.Options OPTIONS = IclockProperties.Options.defaults();

  @Test
  void enablesRealtimePushWhichIsThePointOfTheHandshake() {
    String block = IclockOptions.block("ABC123", 1_700_000_000L, OPTIONS);

    // Without Realtime=1 the device only uploads at its scheduled TransTimes slots.
    assertThat(block).contains("Realtime=1");
  }

  @Test
  void echoesTheSerialInTheHeaderLineTheFirmwareExpects() {
    String block = IclockOptions.block("ABC123", 1L, OPTIONS);

    assertThat(block).startsWith("GET OPTION FROM: ABC123\r\n");
  }

  @Test
  void usesCrlfLineEndings() {
    String block = IclockOptions.block("ABC123", 1L, OPTIONS);

    assertThat(block).endsWith("\r\n");
    // Every newline is part of a CRLF pair — no bare LF anywhere.
    assertThat(block.replace("\r\n", "")).doesNotContain("\n");
  }

  @Test
  void carriesTheStampAndTheConfiguredOptionKeys() {
    String block = IclockOptions.block("ABC123", 42L, OPTIONS);

    assertThat(block)
        .contains("Stamp=42")
        .contains("ATTLOGStamp=42")
        .contains("Delay=10")
        .contains("TransFlag=1111000000")
        .contains("TimeZone=5.5");
  }

  @Test
  void survivesAMissingSerialRatherThanPrintingNull() {
    String block = IclockOptions.block(null, 1L, OPTIONS);

    assertThat(block).startsWith("GET OPTION FROM: \r\n").doesNotContain("null");
  }
}
