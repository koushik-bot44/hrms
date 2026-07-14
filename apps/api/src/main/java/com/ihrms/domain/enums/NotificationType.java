package com.ihrms.domain.enums;

/** Notification kinds driving the Manager inbox (§4). PG enum type {@code "NotificationType"}. */
public enum NotificationType {
  EMPLOYEE_ONBOARDED,
  EMPLOYEE_SUBMITTED,
  APPROVAL_REQUESTED,
  EMPLOYEE_APPROVED,
  EMPLOYEE_REJECTED,
  // §8b — a Manager bell entry when one of his team's employees submits a leave request.
  LEAVE_REQUESTED
}
