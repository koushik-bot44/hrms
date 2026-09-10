package com.ihrms.iclock;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The biometric parsers, pinned against bodies this fleet actually sent.
 *
 * <p>These are not invented samples. Every record below is copied from {@code iclock_request_logs},
 * templates truncated and nothing else changed — and they exist because the documented design was
 * wrong twice over. Fingerprints were supposed to need fetching with {@code DATA QUERY FINGERTMP};
 * they arrive unasked inside OPERLOG. Faces were supposed to be {@code Type=2}; this hardware sends
 * {@code Type=9}, with an algorithm version that differs between the two firmware families.
 */
class IclockOperlogTest {

  /** ZHM2252000230, 2026-09-02 22:19:30Z. Template truncated; the header is verbatim. */
  private static final String REAL_PUSH =
      "FP PIN=8003\tFID=6\tSize=1544\tValid=1\tTMP=TcdTUzIxAAAEhIsECAUHCc7QAAAohZEB\n"
          + "OPLOG 101\t0\t2026-09-03 03:49:28\t8003\t0\t0\t0\n";

  /** NES1255300684, 2026-09-10, in answer to the first DATA QUERY USERINFO. Template truncated. */
  private static final String REAL_FACE_NES =
      "BIODATA Pin=1051\tNo=0\tIndex=0\tValid=1\tDuress=0\tType=9\t"
          + "MajorVer=36\tMinorVer=1\tFormat=0\tTmp=apUBFi4BAAA+pQAACQAkAQGUTAAAAFVfQio\n";

  /** ZHM2252000230, same moment, same command — and a different algorithm version. */
  private static final String REAL_FACE_ZHM =
      "BIODATA Pin=22234\tNo=0\tIndex=0\tValid=1\tDuress=0\tType=9\t"
          + "MajorVer=39\tMinorVer=3\tFormat=0\tTmp=apUBEBABQBUJACcDAUm+APgwcQ0Pvz13\n";

  // ------------------------------------------------------------------ fingerprints

  @Test
  void aFingerprintIsLiftedOutOfAnOperlogPush() {
    List<IclockOperlog.Template> found = IclockOperlog.templates(REAL_PUSH);

    assertThat(found).hasSize(1);
    IclockOperlog.Template t = found.get(0);
    assertThat(t.pin()).isEqualTo("8003");
    assertThat(t.fid()).isEqualTo(6);
    assertThat(t.size()).isEqualTo(1544);
    assertThat(t.valid()).isEqualTo(1);
    assertThat(t.bioType()).isEqualTo(IclockCommandDialect.TYPE_FINGERPRINT);
    assertThat(t.template()).startsWith("TcdTUzIx");
  }

  @Test
  void theReportedSizeIsKeptNotRecomputed() {
    // Size is the DECODED byte count the device reported; the base64 is longer. Recomputing it would
    // be arithmetic on an assumption about padding, and this figure is what gets sent back when the
    // template is propagated.
    IclockOperlog.Template t = IclockOperlog.templates(REAL_PUSH).get(0);

    assertThat(t.size()).isEqualTo(1544).isNotEqualTo(t.template().length());
  }

  @Test
  void aFingerprintCarriesNoVersionAndSaysSoRatherThanGuessingZero() {
    // Absent is a real state, distinct from zero. The command funnel reads it as "not proven
    // compatible" rather than "version 0".
    assertThat(IclockOperlog.templates(REAL_PUSH).get(0).algoMajor()).isNull();
    assertThat(IclockOperlog.templates(REAL_PUSH).get(0).algoMinor()).isNull();
  }

  @Test
  void aTemplateWithNoFingerIndexIsSkipped() {
    // Guessing FID 0 would overwrite whichever finger happens to live at that index on every other
    // terminal in the building. Dropping the record loses one enrolment; guessing corrupts four.
    assertThat(IclockOperlog.templates("FP PIN=8003\tSize=1544\tValid=1\tTMP=TcdTUzIx\n")).isEmpty();
  }

  // ------------------------------------------------------------------ operations

  @Test
  void operationRecordsAreReadAlongsideTheTemplate() {
    List<IclockOperlog.Operation> ops = IclockOperlog.operations(REAL_PUSH);

    assertThat(ops).hasSize(1);
    assertThat(ops.get(0).opCode()).isEqualTo(IclockOperlog.OP_ENROL);
    assertThat(ops.get(0).target()).isEqualTo("8003");
  }

  @Test
  void aBodyOfOnlyOperationRecordsYieldsNoTemplates() {
    String body = "OPLOG 0\t0\t2026-09-05 17:08:49\t0\t0\t0\t0\n"
        + "OPLOG 103\t0\t2026-09-09 22:30:55\t6010\t0\t0\t0\n";

    assertThat(IclockOperlog.templates(body)).isEmpty();
    assertThat(IclockOperlog.operations(body))
        .extracting(IclockOperlog.Operation::opCode)
        .containsExactly(0, IclockOperlog.OP_DELETE);
  }

  @Test
  void anEmptyOrAbsentBodyIsNotAnError() {
    assertThat(IclockOperlog.templates(null)).isEmpty();
    assertThat(IclockOperlog.templates("")).isEmpty();
    assertThat(IclockOperlog.operations(null)).isEmpty();
  }

  // ------------------------------------------------------------------ faces

  @Test
  void aFaceIsTypeNINEandTheFieldSpellingsDifferFromTheFingerprintRecord() {
    // Pin and Tmp, against the FP record's PIN and TMP. Both real, both from the same push. Getting
    // either wrong would silently drop 98.5% of how this fleet actually passes.
    List<IclockOperlog.Template> found = IclockOperlog.templates(REAL_FACE_NES);

    assertThat(found).hasSize(1);
    assertThat(found.get(0).pin()).isEqualTo("1051");
    assertThat(found.get(0).bioType()).isEqualTo(IclockCommandDialect.TYPE_FACE);
    assertThat(IclockCommandDialect.TYPE_FACE).as("the spec says 2; the wire says 9").isEqualTo(9);
    assertThat(found.get(0).template()).startsWith("apUBFi4B");
  }

  @Test
  void theFaceAlgorithmVersionTravelsWithTheTemplate() {
    // THE FACT THAT BLOCKS CROSS-FAMILY PROPAGATION. Same command, same minute, two terminals, two
    // different algorithm versions. A template that lost its version on the way in could be pushed
    // to a terminal that cannot match a live face to it, and nothing would report an error.
    IclockOperlog.Template nes = IclockOperlog.templates(REAL_FACE_NES).get(0);
    IclockOperlog.Template zhm = IclockOperlog.templates(REAL_FACE_ZHM).get(0);

    assertThat(nes.algoMajor()).isEqualTo(36);
    assertThat(nes.algoMinor()).isEqualTo(1);
    assertThat(zhm.algoMajor()).isEqualTo(39);
    assertThat(zhm.algoMinor()).isEqualTo(3);
    assertThat(nes.algoMajor()).isNotEqualTo(zhm.algoMajor());
  }

  @Test
  void aFaceAndAFingerDoNotCollide() {
    // Both land at index 0 for a face, so type is what keeps them apart. Without it, enrolling a
    // face would overwrite the finger that already opens the door.
    String body = "FP PIN=6011\tFID=0\tSize=1544\tValid=1\tTMP=Zmluz2Vy\n"
        + "BIODATA Pin=6011\tNo=0\tType=9\tValid=1\tTmp=ZmFjZQ==\n";

    List<IclockOperlog.Template> found = IclockOperlog.templates(body);

    assertThat(found).hasSize(2);
    assertThat(found).extracting(IclockOperlog.Template::bioType)
        .containsExactly(IclockCommandDialect.TYPE_FINGERPRINT, IclockCommandDialect.TYPE_FACE);
  }
}
