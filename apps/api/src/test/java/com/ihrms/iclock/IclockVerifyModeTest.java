package com.ihrms.iclock;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The verify-mode map, pinned to what this fleet actually sends.
 *
 * <p>Four codes across 230,461 punches. The map is narrow on purpose, and these assertions exist to
 * keep it narrow: the temptation with a firmware vocabulary is to fill in the gaps from a
 * specification, and a punch relabelled from a guess goes straight into an attendance dispute where
 * somebody is asked to explain a credential they never used.
 */
class IclockVerifyModeTest {

  @Test
  void theFourCodesThisFleetHasEverSent() {
    // 15 is 98.52% of all traffic; 1 is 1.12%; 4 is 0.36%; 25 happened once, on 2026-08-14.
    assertThat(IclockVerifyMode.of(15)).isEqualTo(IclockVerifyMode.FACE);
    assertThat(IclockVerifyMode.of(1)).isEqualTo(IclockVerifyMode.FINGER);
    assertThat(IclockVerifyMode.of(4)).isEqualTo(IclockVerifyMode.CARD);
    assertThat(IclockVerifyMode.of(25)).isEqualTo(IclockVerifyMode.PALM);
  }

  @Test
  void aCodeNobodyHasSeenIsOTHERratherThanTheNearestFamiliarThing() {
    // The combined modes (fingerprint-or-password and friends) are firmware-specific. Showing
    // "Other" for one is information; showing "Finger" is a fabrication.
    assertThat(IclockVerifyMode.of(6)).isEqualTo(IclockVerifyMode.OTHER);
    assertThat(IclockVerifyMode.of(11)).isEqualTo(IclockVerifyMode.OTHER);
    assertThat(IclockVerifyMode.of(99)).isEqualTo(IclockVerifyMode.OTHER);
    assertThat(IclockVerifyMode.of(-1)).isEqualTo(IclockVerifyMode.OTHER);
  }

  @Test
  void theRawColumnIsAStringAndMayBeMissingEntirely() {
    // Runs over history, and the earliest captures predate any guarantee about which columns a
    // firmware fills in. A null must not take down a feed page.
    assertThat(IclockVerifyMode.of("15")).isEqualTo(IclockVerifyMode.FACE);
    assertThat(IclockVerifyMode.of(" 1 ")).isEqualTo(IclockVerifyMode.FINGER);
    assertThat(IclockVerifyMode.of((String) null)).isEqualTo(IclockVerifyMode.OTHER);
    assertThat(IclockVerifyMode.of("")).isEqualTo(IclockVerifyMode.OTHER);
    assertThat(IclockVerifyMode.of("not-a-number")).isEqualTo(IclockVerifyMode.OTHER);
    assertThat(IclockVerifyMode.of((Integer) null)).isEqualTo(IclockVerifyMode.OTHER);
  }

  @Test
  void everyModeCarriesAWordAnOperatorReads() {
    for (IclockVerifyMode m : IclockVerifyMode.values()) {
      assertThat(m.label()).isNotBlank();
    }
    assertThat(IclockVerifyMode.FACE.label()).isEqualTo("Face");
    assertThat(IclockVerifyMode.FINGER.label()).isEqualTo("Finger");
  }
}
