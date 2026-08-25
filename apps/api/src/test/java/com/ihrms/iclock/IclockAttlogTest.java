package com.ihrms.iclock;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The ATTLOG parser must never reject a line — this firmware's exact column layout is unknown, and
 * losing a punch to a parse failure is the one outcome P0 cannot accept. No Spring context, so these
 * run even without a local Postgres.
 */
class IclockAttlogTest {

  @Test
  void parsesTheDocumentedFiveColumnLayout() {
    List<IclockAttlog.Line> lines =
        IclockAttlog.parse("101\t2026-08-25 09:15:00\t0\t1\t0\n");

    assertThat(lines).hasSize(1);
    IclockAttlog.Line line = lines.get(0);
    assertThat(line.lineNumber()).isEqualTo(1);
    assertThat(line.pin()).isEqualTo("101");
    assertThat(line.punchedAtRaw()).isEqualTo("2026-08-25 09:15:00");
    assertThat(line.statusCode()).isEqualTo("0");
    assertThat(line.verifyMode()).isEqualTo("1");
    assertThat(line.workCode()).isEqualTo("0");
  }

  @Test
  void keepsRawLineAndNullsMissingColumnsOnAShortLine() {
    List<IclockAttlog.Line> lines = IclockAttlog.parse("101\t2026-08-25 09:15:00");

    assertThat(lines).hasSize(1);
    IclockAttlog.Line line = lines.get(0);
    assertThat(line.pin()).isEqualTo("101");
    assertThat(line.punchedAtRaw()).isEqualTo("2026-08-25 09:15:00");
    assertThat(line.statusCode()).isNull();
    assertThat(line.verifyMode()).isNull();
    assertThat(line.workCode()).isNull();
    // The whole line survives even though the projection is incomplete.
    assertThat(line.rawLine()).isEqualTo("101\t2026-08-25 09:15:00");
  }

  @Test
  void toleratesExtraTrailingColumnsFromNewerFirmware() {
    String raw = "101\t2026-08-25 09:15:00\t0\t1\t0\t99\t36.5";
    List<IclockAttlog.Line> lines = IclockAttlog.parse(raw);

    assertThat(lines).hasSize(1);
    assertThat(lines.get(0).pin()).isEqualTo("101");
    assertThat(lines.get(0).workCode()).isEqualTo("0");
    // Columns we do not project are not lost — rawLine is the authoritative record.
    assertThat(lines.get(0).rawLine()).isEqualTo(raw);
  }

  @Test
  void skipsBlankLinesAndNumbersOnlyWhatIsKept() {
    List<IclockAttlog.Line> lines =
        IclockAttlog.parse("101\t2026-08-25 09:15:00\t0\t1\t0\n\n  \n102\t2026-08-25 09:16:00\t1\t1\t0\n");

    assertThat(lines).hasSize(2);
    assertThat(lines.get(0).lineNumber()).isEqualTo(1);
    assertThat(lines.get(1).lineNumber()).isEqualTo(2);
    assertThat(lines.get(1).pin()).isEqualTo("102");
  }

  @Test
  void handlesCrlfAndBareCrTerminators() {
    assertThat(IclockAttlog.parse("101\t09:15\r\n102\t09:16\r\n")).hasSize(2);
    assertThat(IclockAttlog.parse("101\t09:15\r102\t09:16")).hasSize(2);
  }

  @Test
  void treatsBlankColumnsAsNullButKeepsThePosition() {
    List<IclockAttlog.Line> lines = IclockAttlog.parse("101\t\t0\t1\t0");

    assertThat(lines.get(0).pin()).isEqualTo("101");
    assertThat(lines.get(0).punchedAtRaw()).isNull();
    // The blank did not shift the later columns along.
    assertThat(lines.get(0).statusCode()).isEqualTo("0");
  }

  @Test
  void returnsEmptyForNullOrEmptyBodies() {
    assertThat(IclockAttlog.parse(null)).isEmpty();
    assertThat(IclockAttlog.parse("")).isEmpty();
    assertThat(IclockAttlog.parse("\n\n")).isEmpty();
  }
}
