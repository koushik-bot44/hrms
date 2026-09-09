package com.ihrms.iclock;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses a {@code table=USERINFO} push — the only way a device's own name for somebody can reach us.
 *
 * <p><b>What the fleet actually sends today, measured.</b> Across 129,747 logged requests these
 * terminals have pushed exactly two tables, ATTLOG and OPERLOG, and no body has ever contained
 * {@code Name=} or {@code USERINFO}. The OPERLOG rows are OPLOG operation records — numeric op-code,
 * operator, timestamp and a pin, e.g. {@code OPLOG 4\t19041\t2026-09-09 22:31:22\t19041\t0\t0\t0} —
 * so they say something happened to a pin, and never what that person is called.
 *
 * <p>So this parser has no live input yet. It is written anyway because it is the receiving half of
 * the only mechanism that could ever supply one, and because the absence is not proof: no enrolment
 * appears to have happened on this fleet since adoption, and this firmware may well push USERINFO
 * when one does. If it does, the names land the moment it happens. If it does not, the same parser
 * serves the {@code DATA QUERY USERINFO} route, which asks a terminal for its user table over the P3
 * command channel.
 *
 * <p>Pure and Spring-free so the format can be pinned against real captures without a device.
 */
final class IclockUserInfo {

  private IclockUserInfo() {}

  /**
   * One enrolment as the device holds it.
   *
   * @param pin canonical, or null when the record carried nothing usable
   * @param name the device's own label — a register string, often a slug, and never authoritative
   * @param card card number, when present
   * @param privilege 0 for an ordinary user; higher values are operators and admins
   */
  record Record(String pin, String name, String card, Integer privilege) {}

  /**
   * Parses a USERINFO body into records, skipping anything unusable.
   *
   * <p>The wire shape is one record per line, tab-separated {@code key=value} pairs, conventionally
   * introduced by {@code USER}:
   *
   * <pre>
   *   USER PIN=19041\tName=Boorla Sai Manoj\tPri=0\tPasswd=\tCard=123456\tGrp=1\tTZ=
   * </pre>
   *
   * <p>Unknown keys are ignored rather than rejected: firmware differs in which fields it includes,
   * and a record that carries one extra column is still a usable name.
   */
  static List<Record> parse(String body) {
    List<Record> out = new ArrayList<>();
    if (body == null || body.isBlank()) {
      return out;
    }
    for (String line : body.split("\\r?\\n")) {
      String trimmed = line.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      // Drop a leading record-type word ("USER", "USERINFO") if present. Some firmware omits it.
      int firstEq = trimmed.indexOf('=');
      if (firstEq < 0) {
        continue;
      }
      int firstTab = trimmed.indexOf('\t');
      if (firstTab > 0 && firstTab < firstEq) {
        // The '=' belongs to a later field, so the first column is a bare record type.
        trimmed = trimmed.substring(firstTab + 1);
      } else {
        int sp = trimmed.lastIndexOf(' ', firstEq);
        if (sp > 0) {
          trimmed = trimmed.substring(sp + 1);
        }
      }

      String pin = null;
      String name = null;
      String card = null;
      Integer pri = null;
      for (String field : trimmed.split("\t")) {
        int eq = field.indexOf('=');
        if (eq <= 0) {
          continue;
        }
        String key = field.substring(0, eq).trim();
        String value = field.substring(eq + 1).trim();
        if (key.equalsIgnoreCase("PIN")) {
          pin = IclockPin.canonicalOrNull(value);
        } else if (key.equalsIgnoreCase("Name")) {
          name = value.isEmpty() ? null : value;
        } else if (key.equalsIgnoreCase("Card")) {
          card = value.isEmpty() ? null : value;
        } else if (key.equalsIgnoreCase("Pri")) {
          try {
            pri = Integer.valueOf(value);
          } catch (NumberFormatException ignored) {
            // A privilege we cannot read is not worth failing a name over.
          }
        }
      }
      if (pin != null) {
        out.add(new Record(pin, name, card, pri));
      }
    }
    return out;
  }

  /**
   * Whether a device-supplied name is worth seeding a roster row with.
   *
   * <p>These registers are full of junk: pure digits, the pin repeated back, single letters, and
   * placeholder words. Seeding "18402" as somebody's name is worse than leaving them unnamed, because
   * an unnamed person shows in the console as work-to-do while a numerically-named one looks done.
   */
  static boolean isUsableName(String pin, String name) {
    if (name == null) {
      return false;
    }
    String n = name.trim();
    if (n.length() < 2) {
      return false;
    }
    if (n.equals(pin)) {
      return false;
    }
    // All digits, or digits and punctuation only — a register artefact, not a person.
    return !n.matches("[\\d\\p{Punct}\\s]+");
  }
}
