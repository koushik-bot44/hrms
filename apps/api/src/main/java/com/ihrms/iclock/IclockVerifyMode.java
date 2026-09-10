package com.ihrms.iclock;

/**
 * How somebody proved who they were at the terminal — the {@code verifyMode} column of an ATTLOG
 * line, decoded.
 *
 * <p><b>Measured on this fleet, not assumed.</b> Across 230,461 punches exactly four codes have ever
 * appeared, and their shares are the reason this class exists:
 *
 * <pre>
 *   15   FACE     227,040   98.52%
 *    1   FINGER     2,588    1.12%
 *    4   CARD         832    0.36%
 *   25   PALM           1    one punch, 2026-08-14, pin 22234
 * </pre>
 *
 * <p>The headline is that <b>this is a face fleet</b>. Fingerprint is a rounding error in daily use,
 * which matters well beyond a badge on a row: the enrolment work was built around fingerprint
 * templates because those are the ones that reach the server by themselves, and those templates
 * cover about one percent of how people actually get through the door.
 *
 * <p>The mapping follows the ZK verify-mode vocabulary, and the four codes above are the ones this
 * hardware has actually produced. Anything else is reported as its own number rather than guessed
 * at — a punch that got in by some means we have not seen is worth showing as unrecognised, not
 * quietly relabelled as the nearest familiar thing.
 */
public enum IclockVerifyMode {

  /** 98.5% of this fleet's traffic. */
  FACE("Face"),

  /** The modality the enrolment channel can actually replicate — and 1.1% of passes. */
  FINGER("Finger"),

  CARD("Card"),

  PALM("Palm"),

  PASSWORD("Password"),

  /** A code this fleet has never sent. Shown as-is rather than folded into a neighbour. */
  OTHER("Other");

  private final String label;

  IclockVerifyMode(String label) {
    this.label = label;
  }

  /** The word an operator reads. */
  public String label() {
    return label;
  }

  /**
   * Decodes a raw {@code verifyMode}.
   *
   * <p>Null is {@link #OTHER} rather than an exception: this runs over historical rows, and the
   * earliest captures predate any guarantee about which columns a firmware fills in.
   */
  public static IclockVerifyMode of(Integer code) {
    if (code == null) {
      return OTHER;
    }
    // DELIBERATELY NARROW. The four codes this fleet sends, plus the three typed-credential codes
    // the ZK vocabulary assigns unambiguously. The combined modes (fingerprint-or-password and
    // friends) are firmware-specific and are NOT guessed at: showing "Other" for a punch nobody has
    // seen before is information, while showing "Finger" for it is a fabrication that would go
    // straight into an attendance dispute.
    return switch (code) {
      case 1 -> FINGER;
      case 4 -> CARD;
      case 15 -> FACE;
      case 25 -> PALM;
      case 0, 2, 3 -> PASSWORD;
      default -> OTHER;
    };
  }

  /** Convenience for the string form the raw table stores. */
  public static IclockVerifyMode of(String code) {
    if (code == null || code.isBlank()) {
      return OTHER;
    }
    try {
      return of(Integer.valueOf(code.trim()));
    } catch (NumberFormatException e) {
      return OTHER;
    }
  }
}
