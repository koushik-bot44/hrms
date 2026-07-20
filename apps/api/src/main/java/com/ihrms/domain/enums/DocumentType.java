package com.ihrms.domain.enums;

/**
 * Form 4 document slots (§4). PG enum type {@code "DocumentType"}. The per-employment slots
 * ({@code OFFER_OR_APPOINTMENT_LETTER}, {@code HIKE_LETTER}, {@code RELIEVING_LETTER}) are
 * distinguished by a {@code groupIndex} (1..4) on the document row.
 */
public enum DocumentType {
  // Educational
  SECONDARY,
  INTERMEDIATE,
  DIPLOMA,
  GRADUATION,
  POST_GRADUATION,
  // Employment (per-group, with groupIndex 1..4)
  OFFER_OR_APPOINTMENT_LETTER,
  HIKE_LETTER,
  RELIEVING_LETTER,
  // Identity proofs
  AADHAAR,
  PAN,
  VOTER_ID,
  DRIVING_LICENCE,
  PASSPORT,
  ITR,
  // Free / other
  OTHER
}
