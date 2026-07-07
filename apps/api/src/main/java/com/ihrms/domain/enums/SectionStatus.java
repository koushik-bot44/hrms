package com.ihrms.domain.enums;

/** Profile section review state (§4). PG enum type {@code "SectionStatus"}. */
public enum SectionStatus {
  DRAFT,
  SUBMITTED,
  VERIFIED,
  /** HR sent this form back for changes (§3.3); the employee may re-edit only this item. */
  REVISION_REQUESTED,
  REJECTED
}
