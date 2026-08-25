package com.ihrms.iclock;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The one place a punch's dedupe key is computed. Live ingest and the one-off replay both call this,
 * which is what makes it impossible for them to double-count the same line.
 *
 * <p>Deliberately matches the SQL backfill in V42 byte for byte —
 * {@code md5(serialNumber || E'\n' || rawLine)} — so rows written before P0.1 carry keys identical to
 * the ones this class produces. Any change here must change that migration's expression too, or old
 * and new rows stop colliding and history re-imports.
 *
 * <p>md5 is used as a content fingerprint, NOT as a security primitive: the input is device-supplied
 * attendance text, an adversary gains nothing from a collision, and matching Postgres's built-in
 * {@code md5()} avoids requiring the pgcrypto extension purely for a backfill.
 */
final class IclockDedupe {

  private IclockDedupe() {}

  /**
   * <b>Accepted trade-off — same-second collapse.</b> Because the key is the line content, two
   * genuinely distinct punches from the SAME pin in the SAME second with the same status/verify
   * fields are byte-identical and collapse into one row. The device timestamp has one-second
   * resolution and carries no per-record id, so a real repeat and a duplicate upload are
   * indistinguishable at this layer — there is no signal that could separate them. This is
   * deliberate and ratified: losing that rare repeat is preferable to double-counting a 17k-line
   * backlog every time a terminal re-uploads its history. If Phase 1 ever needs true repeats, it
   * must come from a firmware that emits a record id, not from changing this key.
   *
   * @param serialNumber the device serial; a null or blank serial still yields a stable key so a
   *     malformed push cannot bypass dedupe entirely
   * @param rawLine the verbatim ATTLOG line
   */
  static String key(String serialNumber, String rawLine) {
    String input = (serialNumber == null ? "" : serialNumber) + "\n" + (rawLine == null ? "" : rawLine);
    try {
      MessageDigest md5 = MessageDigest.getInstance("MD5");
      return HexFormat.of().formatHex(md5.digest(input.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      // MD5 is mandated by the JDK spec; unreachable on any conformant runtime.
      throw new IllegalStateException("MD5 unavailable", e);
    }
  }
}
