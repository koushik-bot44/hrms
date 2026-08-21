package com.ihrms.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Pure unit test (no Spring, no DB) — runs on every build. Covers the explicit-key crypto that the one-time
 * re-key migration (FieldKeyReencryptor) relies on.
 */
class FieldCryptoTest {

  @Test
  void roundTripsUnderAGivenKey() {
    String c = FieldCrypto.encryptWithKey("ABCDE1234F", "key-one");
    assertThat(c).startsWith(FieldCrypto.PREFIX);
    assertThat(FieldCrypto.decryptWithKey(c, "key-one")).isEqualTo("ABCDE1234F");
  }

  @Test
  void wrongKeyFailsToDecrypt() {
    String c = FieldCrypto.encryptWithKey("secret-value", "key-one");
    assertThatThrownBy(() -> FieldCrypto.decryptWithKey(c, "key-two"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void reEncryptingFromOneKeyToAnotherChangesReadability() {
    String underOld = FieldCrypto.encryptWithKey("1500000", "old-key");
    // simulate the migration: decrypt with old, re-encrypt with new
    String plain = FieldCrypto.decryptWithKey(underOld, "old-key");
    String underNew = FieldCrypto.encryptWithKey(plain, "new-key");
    assertThat(FieldCrypto.decryptWithKey(underNew, "new-key")).isEqualTo("1500000");
    assertThatThrownBy(() -> FieldCrypto.decryptWithKey(underNew, "old-key"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void nullPlaintextAndNonPrefixedPassThrough() {
    assertThat(FieldCrypto.encryptWithKey(null, "k")).isNull();
    assertThat(FieldCrypto.encryptWithKey("", "k")).isEmpty();
    assertThat(FieldCrypto.decryptWithKey("plain-no-prefix", "k")).isEqualTo("plain-no-prefix");
    assertThat(FieldCrypto.decryptWithKey(null, "k")).isNull();
  }

  @Test
  void neverDoubleWraps() {
    String c = FieldCrypto.encryptWithKey("x", "k");
    assertThat(FieldCrypto.encryptWithKey(c, "k")).isEqualTo(c);
    assertThat(FieldCrypto.isEncrypted(c)).isTrue();
    assertThat(FieldCrypto.isEncrypted("plain")).isFalse();
  }
}
