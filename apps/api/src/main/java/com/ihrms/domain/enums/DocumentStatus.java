package com.ihrms.domain.enums;

/** Document verification state (§4). PG enum type {@code "DocumentStatus"} ({@code PENDING} added later). */
public enum DocumentStatus {
  PENDING,
  UPLOADED,
  VERIFIED,
  REJECTED
}
