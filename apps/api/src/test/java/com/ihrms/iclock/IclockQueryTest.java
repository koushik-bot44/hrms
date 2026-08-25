package com.ihrms.iclock;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Query parsing is done by hand rather than with {@code @RequestParam} so that a
 * form-urlencoded POST body is never consumed by the parameter map. These cover the shapes a
 * terminal might realistically send.
 */
class IclockQueryTest {

  @Test
  void readsASerialFromATypicalHandshakeQuery() {
    assertThat(IclockQuery.param("SN=ABC123&options=all&pushver=2.4.1", "SN")).isEqualTo("ABC123");
  }

  @Test
  void matchesTheKeyCaseInsensitivelyBecauseFirmwareCasingVaries() {
    assertThat(IclockQuery.param("sn=ABC123", "SN")).isEqualTo("ABC123");
    assertThat(IclockQuery.param("SN=ABC123&TABLE=ATTLOG", "table")).isEqualTo("ATTLOG");
  }

  @Test
  void findsAKeyThatIsNotFirst() {
    assertThat(IclockQuery.param("Stamp=9999&SN=ABC123&table=ATTLOG", "table")).isEqualTo("ATTLOG");
  }

  @Test
  void returnsNullForAbsentBlankOrValuelessKeys() {
    assertThat(IclockQuery.param("SN=ABC123", "table")).isNull();
    assertThat(IclockQuery.param("SN=", "SN")).isNull();
    assertThat(IclockQuery.param("SN", "SN")).isNull();
    assertThat(IclockQuery.param(null, "SN")).isNull();
    assertThat(IclockQuery.param("", "SN")).isNull();
  }

  @Test
  void urlDecodesValues() {
    assertThat(IclockQuery.param("SN=AB%20C", "SN")).isEqualTo("AB C");
  }

  @Test
  void keepsTheRawFormRatherThanThrowingOnAMalformedEscape() {
    // A stray '%' must not break ingest for every other field on the request.
    assertThat(IclockQuery.param("SN=AB%ZZ", "SN")).isEqualTo("AB%ZZ");
  }
}
