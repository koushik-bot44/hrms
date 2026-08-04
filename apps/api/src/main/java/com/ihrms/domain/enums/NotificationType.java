package com.ihrms.domain.enums;

/** Notification kinds driving the Manager inbox (§4). PG enum type {@code "NotificationType"}. */
public enum NotificationType {
  EMPLOYEE_ONBOARDED,
  EMPLOYEE_SUBMITTED,
  APPROVAL_REQUESTED,
  EMPLOYEE_APPROVED,
  EMPLOYEE_REJECTED,
  // §8b — a Manager bell entry when one of his team's employees submits a leave request.
  LEAVE_REQUESTED,
  // §Agreements — a durable record for the SENDING HR when an employee completes a sent agreement. HR has no
  // bell feed yet (the Manager inbox is the only Notification consumer); HR is reached by push + email, and
  // this row is the durable trail + future-proofs an HR feed.
  AGREEMENT_COMPLETED
}
