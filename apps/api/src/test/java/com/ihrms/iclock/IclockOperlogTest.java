package com.ihrms.iclock;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The OPERLOG parser, pinned against bodies this fleet actually sent.
 *
 * <p>These are not invented samples. Every record below is copied from
 * {@code iclock_request_logs} — the templates truncated, nothing else changed. The feature they
 * support exists BECAUSE of what is in them: the plan assumed fingerprints would have to be
 * requested with {@code DATA QUERY FINGERTMP} and would return as {@code table=BIODATA}, and the
 * request log says otherwise on both counts.
 */
class IclockOperlogTest {

  /** ZHM2252000230, 2026-09-02 22:19:30Z. Template truncated; the header is verbatim. */
  private static final String REAL_PUSH =
      "FP PIN=8003\tFID=6\tSize=1544\tValid=1\tTMP=TcdTUzIxAAAEhIsECAUHCc7QAAAohZEB\n"
          + "OPLOG 101\t0\t2026-09-03 03:49:28\t8003\t0\t0\t0\n";

  // ------------------------------------------------------------------ templates

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
  void operationRecordsAreReadAlongsideTheTemplate() {
    List<IclockOperlog.Operation> ops = IclockOperlog.operations(REAL_PUSH);

    assertThat(ops).hasSize(1);
    assertThat(ops.get(0).opCode()).isEqualTo(IclockOperlog.OP_ENROL);
    assertThat(ops.get(0).target()).isEqualTo("8003");
  }

  @Test
  void aBodyOfOnlyOperationRecordsYieldsNoTemplates() {
    // The overwhelmingly common case: 55 OPERLOG pushes on this fleet, 5 of which carried a finger.
    String body = "OPLOG 0\t0\t2026-09-05 17:08:49\t0\t0\t0\t0\n"
        + "OPLOG 103\t0\t2026-09-09 22:30:55\t6010\t0\t0\t0\n";

    assertThat(IclockOperlog.templates(body)).isEmpty();
    assertThat(IclockOperlog.operations(body))
        .extracting(IclockOperlog.Operation::opCode)
        .containsExactly(0, IclockOperlog.OP_DELETE);
  }

  @Test
  void aTemplateWithNoFingerIndexIsSkipped() {
    // Guessing FID 0 would overwrite whichever finger happens to live at that index on every other
    // terminal in the building. Dropping the record loses one enrolment; guessing corrupts four.
    String body = "FP PIN=8003\tSize=1544\tValid=1\tTMP=TcdTUzIx\n";

    assertThat(IclockOperlog.templates(body)).isEmpty();
  }

  @Test
  void anEmptyOrAbsentBodyIsNotAnError() {
    assertThat(IclockOperlog.templates(null)).isEmpty();
    assertThat(IclockOperlog.templates("")).isEmpty();
    assertThat(IclockOperlog.operations(null)).isEmpty();
  }

  // ------------------------------------------------------------------ faces

  @Test
  void aBiodataRecordIsReadWithItsOwnFieldSpellings() {
    // NEVER SEEN ON THIS FLEET — zero BIODATA pushes in 131,000 requests. The differing case (Pin,
    // Tmp, against the FP record's PIN, TMP) is the specification's, not a slip, and getting it
    // wrong would mean silently dropping the first face that ever arrives.
    String body = "BIODATA Pin=6011\tNo=0\tIndex=0\tValid=1\tDuress=0\tType=2\t"
        + "MajorVer=0\tMinorVer=0\tFormat=0\tTmp=Zm9vYmFy\n";

    List<IclockOperlog.Template> found = IclockOperlog.templates(body);

    assertThat(found).hasSize(1);
    assertThat(found.get(0).pin()).isEqualTo("6011");
    assertThat(found.get(0).bioType()).isEqualTo(IclockCommandDialect.TYPE_FACE);
    assertThat(found.get(0).template()).isEqualTo("Zm9vYmFy");
  }

  @Test
  void aFaceAndAFingerDoNotCollide() {
    // Both land at index 0 for a face, so type is what keeps them apart. Without it, enrolling a
    // face would overwrite the finger that already opens the door.
    String body = "FP PIN=6011\tFID=0\tSize=1544\tValid=1\tTMP=Zmluz2Vy\n"
        + "BIODATA Pin=6011\tNo=0\tType=2\tValid=1\tTmp=ZmFjZQ==\n";

    List<IclockOperlog.Template> found = IclockOperlog.templates(body);

    assertThat(found).hasSize(2);
    assertThat(found).extracting(IclockOperlog.Template::bioType)
        .containsExactly(IclockCommandDialect.TYPE_FINGERPRINT, IclockCommandDialect.TYPE_FACE);
  }
}
