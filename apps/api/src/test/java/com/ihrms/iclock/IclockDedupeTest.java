package com.ihrms.iclock;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The dedupe key is the single thing preventing the one-off replay from double-counting lines that
 * also arrived live, so its behaviour is pinned here. No Spring context required.
 *
 * <p>Parity with the V42 SQL backfill expression ({@code md5(serialNumber || E'\n' || rawLine)}) is
 * asserted against a real Postgres in {@code IclockProtocolTest} — it cannot be checked here without
 * a database, and a drift between the two would silently re-import all history.
 */
class IclockDedupeTest {

  private static final String LINE = "18292\t2026-07-16 01:59:44\t255\t15\t0\t0\t0\t0\t0\t0\t";

  @Test
  void isStableForTheSameSerialAndLine() {
    assertThat(IclockDedupe.key("ZHM2252000230", LINE))
        .isEqualTo(IclockDedupe.key("ZHM2252000230", LINE));
  }

  @Test
  void differsPerSerialSoTwoTerminalsCanReportIdenticalLines() {
    // Two devices legitimately produce the same line text; those must not collapse into one punch.
    assertThat(IclockDedupe.key("DEVICE-A", LINE)).isNotEqualTo(IclockDedupe.key("DEVICE-B", LINE));
  }

  @Test
  void differsWhenAnyFieldOfTheLineDiffers() {
    assertThat(IclockDedupe.key("SN1", "101\t2026-08-25 09:15:00\t255\t15\t0"))
        .isNotEqualTo(IclockDedupe.key("SN1", "101\t2026-08-25 09:15:01\t255\t15\t0"));
    assertThat(IclockDedupe.key("SN1", "101\t2026-08-25 09:15:00\t255\t15\t0"))
        .isNotEqualTo(IclockDedupe.key("SN1", "102\t2026-08-25 09:15:00\t255\t15\t0"));
  }

  @Test
  void isAnMd5HexDigest() {
    // 32 lowercase hex chars — the same shape Postgres md5() returns, which the V42 backfill relies on.
    assertThat(IclockDedupe.key("SN1", LINE)).matches("[0-9a-f]{32}");
  }

  @Test
  void handlesNullsWithoutThrowingSoAMalformedPushCannotBypassDedupe() {
    assertThat(IclockDedupe.key(null, LINE)).matches("[0-9a-f]{32}");
    assertThat(IclockDedupe.key("SN1", null)).matches("[0-9a-f]{32}");
    assertThat(IclockDedupe.key(null, null)).matches("[0-9a-f]{32}");
  }

  @Test
  void separatesSerialFromLineSoFieldsCannotBeShiftedAcrossTheBoundary() {
    // Without the delimiter, ("AB","C") and ("A","BC") would hash identically.
    assertThat(IclockDedupe.key("AB", "C")).isNotEqualTo(IclockDedupe.key("A", "BC"));
  }
}
