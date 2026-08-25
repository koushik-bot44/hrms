package com.ihrms.iclock;

import java.util.ArrayList;
import java.util.List;

/**
 * Tolerant parser for an ATTLOG push body. Pure and static — unit-testable with no Spring context.
 *
 * <p>The documented layout is TAB-separated {@code PIN, timestamp, status, verify, workcode}, but
 * this firmware's exact column count and order are unknown in P0 (post-2020 models append extra
 * reserved/masked/temperature fields, and some emit fewer). The parser therefore never rejects a
 * line: it takes the columns it recognises, leaves the rest null, and always preserves the whole
 * line verbatim in {@link Line#rawLine()}, which is the authoritative record.
 */
final class IclockAttlog {

  private IclockAttlog() {}

  /**
   * One parsed line. Every projected field is nullable; {@code rawLine} never is.
   *
   * @param lineNumber 1-based position within the body, so a batch can be reconstructed in order
   */
  record Line(
      int lineNumber,
      String rawLine,
      String pin,
      String punchedAtRaw,
      String statusCode,
      String verifyMode,
      String workCode) {}

  /**
   * Splits a body into lines and each line into columns. Blank lines are skipped but still advance
   * nothing — numbering follows the lines actually kept, so it matches what is stored.
   *
   * <p>Handles CRLF, LF and bare CR, since the terminating sequence is one of the dialect unknowns.
   */
  static List<Line> parse(String body) {
    List<Line> out = new ArrayList<>();
    if (body == null || body.isEmpty()) {
      return out;
    }
    int n = 0;
    for (String raw : body.split("\r\n|\n|\r", -1)) {
      if (raw.isBlank()) {
        continue;
      }
      // -1 keeps trailing empty columns, so a line ending in a TAB is not silently reshaped.
      String[] c = raw.split("\t", -1);
      out.add(
          new Line(
              ++n,
              raw,
              at(c, 0),
              at(c, 1),
              at(c, 2),
              at(c, 3),
              at(c, 4)));
    }
    return out;
  }

  /** Column at {@code i}, or null when the line is short or the column is blank. */
  private static String at(String[] columns, int i) {
    if (i >= columns.length) {
      return null;
    }
    String v = columns[i].trim();
    return v.isEmpty() ? null : v;
  }
}
