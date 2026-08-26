package com.ihrms.iclock;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * PIN canonicalisation (decision D3). No Spring context.
 *
 * <p>The padded cases here are REAL: mined enrolment records from the live fleet contain
 * {@code 000261}, {@code 000262} and {@code 02919}, and those exact padded forms appear in
 * {@code iclock_raw_punches.devicePin}. Without canonicalising both sides, an operator importing
 * {@code 261} would never match a punch — and the failure is silent, indistinguishable from an
 * unenrolled finger.
 */
class IclockPinTest {

  @ParameterizedTest
  @CsvSource({
    "000261, 261", // observed on the live fleet
    "000262, 262", // observed
    "02919,  2919", // observed
    "18292,  18292",
    "200101, 200101",
    "'  7003  ', 7003",
  })
  void stripsLeadingZerosAndWhitespace(String raw, String expected) {
    assertThat(IclockPin.canonical(raw)).isEqualTo(expected);
  }

  @Test
  void isIdempotent() {
    // Canonicalising an already-canonical pin must not change it, or ingest and mapping would
    // disagree depending on how many times the value passed through.
    assertThat(IclockPin.canonical(IclockPin.canonical("000261"))).isEqualTo("261");
  }

  @Test
  void paddedAndBareFormsConverge() {
    // The whole point: the device's padded form and an operator's bare form must become one key.
    assertThat(IclockPin.canonical("000261")).isEqualTo(IclockPin.canonical("261"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"1", "7003", "18292", "200101", "99999999999999999999"})
  void acceptsWellFormedPins(String pin) {
    assertThat(IclockPin.canonicalOrNull(pin)).isEqualTo(pin);
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "00", "000", "abc", "12a", "12 34", "-1", "1.5", "999999999999999999999"})
  void rejectsAnythingThatCouldNotBeAnEnrolmentNumber(String pin) {
    // An all-zero pin canonicalises to "0" and is then REJECTED rather than silently accepted:
    // mapping it would shadow real punches behind a pin no terminal actually issues.
    assertThat(IclockPin.canonicalOrNull(pin)).isNull();
  }

  @Test
  void handlesNullAndBlank() {
    assertThat(IclockPin.canonical(null)).isNull();
    assertThat(IclockPin.canonical("")).isNull();
    assertThat(IclockPin.canonical("   ")).isNull();
    assertThat(IclockPin.canonicalOrNull(null)).isNull();
  }
}
