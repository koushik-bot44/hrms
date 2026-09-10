package com.ihrms.iclock;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses an {@code table=OPERLOG} push — operation records, and the fingerprint templates that ride
 * along with them.
 *
 * <p><b>THE TEMPLATES WERE ALWAYS THERE.</b> The plan for remote enrolment assumed templates would
 * have to be fetched, with {@code DATA QUERY FINGERTMP}, and would come back as {@code table=BIODATA}.
 * Neither holds on this fleet. It has never sent a BIODATA push — not once in 131,000 requests — and
 * it does not need to be asked: when somebody enrols at a terminal, the template is POSTed
 * unprompted in the same OPERLOG batch as the operation record.
 *
 * <pre>
 *   FP PIN=8003\tFID=6\tSize=1544\tValid=1\tTMP=TcdTUzIxAAAEhIsECAUHCc7QAAAo...
 *   OPLOG 101\t0\t2026-09-03 03:49:28\t8003\t0\t0\t0
 * </pre>
 *
 * <p>Five such templates have arrived since adoption, two of them on 2026-09-09 within three minutes
 * of each other. That is what makes building-wide propagation possible without a capture protocol:
 * the hard half — getting a fingerprint off a device — already happens by itself.
 *
 * <p>Pure and Spring-free, like the ATTLOG and USERINFO parsers, so the format can be pinned against
 * real captured bodies without a device or a database.
 */
final class IclockOperlog {

  private IclockOperlog() {}

  /**
   * One fingerprint template as the terminal serialised it.
   *
   * <p>{@code size} and {@code valid} are the device's own figures, kept verbatim rather than
   * recomputed: they are what has to be sent back when the template is propagated, and a
   * disagreement between the reported size and the decoded length is a fact worth being able to see
   * rather than one to silently correct.
   *
   * @param template base64, exactly as received
   */
  record Template(String pin, int bioType, int fid, int size, int valid, String template) {}

  /**
   * An operation record. The op-code vocabulary is firmware-specific and only partly documented; the
   * codes actually observed on this fleet are 0, 4, 6, 30, 70, 101, 103 and 114.
   *
   * @param opCode the numeric operation
   * @param target the pin the operation concerned, when the record carries one
   */
  record Operation(int opCode, String target, String at) {}

  /** Op-code seen on this fleet immediately before every template push: a user was enrolled. */
  static final int OP_ENROL = 101;

  /** The counterpart, observed 2026-09-09: a user was removed at the terminal. */
  static final int OP_DELETE = 103;

  /**
   * Templates carried in one OPERLOG body.
   *
   * <p>Anything unparseable is skipped rather than rejected. A body mixes operation records and
   * templates freely, and firmware differs in which fields it includes; one malformed line must not
   * cost the rest of a batch — least of all a fingerprint somebody has just stood at a terminal to
   * provide.
   */
  static List<Template> templates(String body) {
    List<Template> out = new ArrayList<>();
    if (body == null || body.isBlank()) {
      return out;
    }
    for (String line : body.split("\\r?\\n")) {
      String trimmed = line.trim();
      Template t = trimmed.startsWith("FP ") ? fingerprint(trimmed)
          : trimmed.startsWith("BIODATA ") ? biodata(trimmed)
          : null;
      if (t != null) {
        out.add(t);
      }
    }
    return out;
  }

  /** {@code FP PIN=..\tFID=..\tSize=..\tValid=..\tTMP=..} — the shape this fleet actually sends. */
  private static Template fingerprint(String line) {
    String pin = IclockPin.canonicalOrNull(field(line, "PIN"));
    String tmp = field(line, "TMP");
    Integer fid = intField(line, "FID");
    if (pin == null || tmp == null || tmp.isBlank() || fid == null) {
      // Without a finger index there is nowhere to put the template on the receiving device, and
      // guessing 0 would overwrite whichever finger happens to live there.
      return null;
    }
    Integer size = intField(line, "Size");
    Integer valid = intField(line, "Valid");
    return new Template(pin, IclockCommandDialect.TYPE_FINGERPRINT, fid,
        size == null ? tmp.length() : size, valid == null ? 1 : valid, tmp);
  }

  /**
   * {@code BIODATA Pin=..\tNo=..\tType=..\tTmp=..} — face and the other modalities.
   *
   * <p>NEVER OBSERVED HERE. Not one BIODATA push exists in the request log, so this is the
   * specification's shape rather than a captured one, and it is written to be ready rather than to
   * be trusted. Note the different capitalisation from the FP record — {@code Pin}, {@code Tmp} —
   * which is the specification's, not a transcription slip.
   */
  private static Template biodata(String line) {
    String pin = IclockPin.canonicalOrNull(field(line, "Pin"));
    String tmp = field(line, "Tmp");
    if (pin == null || tmp == null || tmp.isBlank()) {
      return null;
    }
    Integer no = intField(line, "No");
    Integer type = intField(line, "Type");
    Integer valid = intField(line, "Valid");
    return new Template(pin, type == null ? IclockCommandDialect.TYPE_FACE : type,
        no == null ? 0 : no, tmp.length(), valid == null ? 1 : valid, tmp);
  }

  /** Operation records carried in one OPERLOG body. */
  static List<Operation> operations(String body) {
    List<Operation> out = new ArrayList<>();
    if (body == null || body.isBlank()) {
      return out;
    }
    for (String line : body.split("\\r?\\n")) {
      String trimmed = line.trim();
      if (!trimmed.startsWith("OPLOG ")) {
        continue;
      }
      // OPLOG <code>\t<operator>\t<timestamp>\t<target>\t...
      String[] parts = trimmed.substring("OPLOG ".length()).split("\\t");
      if (parts.length == 0) {
        continue;
      }
      Integer code = parseInt(parts[0].trim());
      if (code == null) {
        continue;
      }
      String at = parts.length > 2 ? parts[2].trim() : null;
      String target = parts.length > 3 ? IclockPin.canonicalOrNull(parts[3].trim()) : null;
      out.add(new Operation(code, target, at));
    }
    return out;
  }

  /** Reads {@code Key=value} from a tab-separated record. */
  private static String field(String line, String key) {
    for (String part : line.split("\\t")) {
      String p = part.trim();
      // The first field carries the record verb too ("FP PIN=8003"), so match on the tail.
      int eq = p.indexOf('=');
      if (eq <= 0) {
        continue;
      }
      String name = p.substring(0, eq).trim();
      if (name.endsWith(key) && (name.equals(key) || name.endsWith(" " + key))) {
        return p.substring(eq + 1);
      }
    }
    return null;
  }

  private static Integer intField(String line, String key) {
    return parseInt(field(line, key));
  }

  private static Integer parseInt(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return Integer.valueOf(raw.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
