package com.ihrms.domain.enums;

/** Document verification state (§4). PG enum type {@code "DocumentStatus"} ({@code PENDING} added later). */
public enum DocumentStatus {
  PENDING,
  UPLOADED,
  VERIFIED,
  /** HR asked for a re-upload (§3.3); the employee may replace only this document. */
  REVISION_REQUESTED,
  REJECTED
}
