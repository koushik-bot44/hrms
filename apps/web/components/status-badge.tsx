import * as React from 'react';
import type { DocumentStatus, OnboardingState } from '@cdpp/shared';
import { Badge, type BadgeProps } from '@/components/ui/badge';
import { cn } from '@/lib/utils';

/**
 * One badge for BOTH document status and onboarding state. The key type is the
 * union of the `@cdpp/shared` enums, so `STATUS_MAP` is checked for exhaustiveness:
 * if either enum gains/renames a member, this fails to compile until updated.
 *
 * Color roles (from the design system): gray=neutral, amber=gated, teal=success,
 * navy=settled/active, red=danger.
 */
type StatusValue = DocumentStatus | OnboardingState;
type Tone = NonNullable<BadgeProps['variant']>;

const STATUS_MAP: Record<StatusValue, { label: string; tone: Tone }> = {
  // DocumentStatus
  DRAFT: { label: 'Draft', tone: 'neutral' },
  PENDING: { label: 'Pending approval', tone: 'warning' },
  ISSUED: { label: 'Issued', tone: 'success' },
  SIGNED: { label: 'Signed', tone: 'navy' },
  REVOKED: { label: 'Revoked', tone: 'danger' },
  PENDING_APPROVAL: { label: 'Pending approval', tone: 'warning' },
  SUPERSEDED: { label: 'Superseded', tone: 'navy' },
  // OnboardingState
  INVITED: { label: 'Invited', tone: 'neutral' },
  IN_PROGRESS: { label: 'In progress', tone: 'navy' },
  PENDING_REVIEW: { label: 'Pending review', tone: 'warning' },
  COMPLETED: { label: 'Completed', tone: 'success' },
  REJECTED: { label: 'Rejected', tone: 'danger' },
  SUBMITTING: { label: 'Submitting', tone: 'navy' },
  UNDER_REVIEW: { label: 'Under review', tone: 'warning' },
  PENDING_COMPLIANCE: { label: 'Pending compliance', tone: 'warning' },
  READY: { label: 'Ready', tone: 'navy' },
  ACTIVE: { label: 'Active', tone: 'success' },
  EXITED: { label: 'Exited', tone: 'neutral' },
};

export interface StatusBadgeProps extends React.HTMLAttributes<HTMLSpanElement> {
  status: StatusValue;
  showDot?: boolean;
}

export function StatusBadge({ status, showDot = true, className, ...props }: StatusBadgeProps) {
  const meta = STATUS_MAP[status];
  return (
    <Badge variant={meta.tone} className={cn(className)} {...props}>
      {showDot ? <span className="size-1.5 rounded-full bg-current" aria-hidden /> : null}
      {meta.label}
    </Badge>
  );
}
