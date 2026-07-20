package com.ihrms.domain.enums;

/** HR/Accounts document-request lifecycle (§8d). PG enum type {@code "RequestStatus"}. */
public enum RequestStatus {
  SUBMITTED,
  IN_PROGRESS,
  RESOLVED,
  CANCELLED
}
