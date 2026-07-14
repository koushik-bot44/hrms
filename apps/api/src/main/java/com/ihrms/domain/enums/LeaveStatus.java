package com.ihrms.domain.enums;

/** Leave request lifecycle (§8b). PG enum type {@code "LeaveStatus"}. */
public enum LeaveStatus {
  PENDING,
  APPROVED,
  REJECTED,
  CANCELLED
}
