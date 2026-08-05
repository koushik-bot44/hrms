package com.ihrms.domain.enums;

/**
 * The final clearance decision on the HR-side offboarding checklist (§3.6 stage 2). PG enum type
 * {@code "ClearanceFinalStatus"} — matches the Clearance Form's "Approved / Pending / On Hold".
 */
public enum ClearanceFinalStatus {
  APPROVED,
  PENDING,
  ON_HOLD
}
