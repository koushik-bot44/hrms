import * as React from 'react';
import type {
  ApprovalStatus,
  DocumentStatus,
  EmployeeStatus,
  SectionStatus,
} from '@ihrms/shared';
import { Badge, type BadgeProps } from '@/components/ui/badge';
import { cn } from '@/lib/utils';

/**
 * One badge for every status across the system. The key type is the union of the four
 * `@ihrms/shared` status enums, so `STATUS_MAP` is checked for exhaustiveness — if any
 * enum gains/renames a member, this fails to compile until updated. Color roles are
 * assigned per status value so the same status always reads the same.
 */
type StatusValue = EmployeeStatus | DocumentStatus | SectionStatus | ApprovalStatus;
type Tone = NonNullable<BadgeProps['variant']>;

const STATUS_MAP: Record<StatusValue, { label: string; tone: Tone }> = {
  // EmployeeStatus
  INVITED: { label: 'Invited', tone: 'neutral' },
  IN_PROGRESS: { label: 'In progress', tone: 'primarySoft' },
  SUBMITTED: { label: 'Submitted', tone: 'warning' },
  HR_VERIFIED: { label: 'HR verified', tone: 'primarySoft' },
  APPROVED: { label: 'Approved', tone: 'success' },
  REJECTED: { label: 'Rejected', tone: 'danger' },
  // DocumentStatus / SectionStatus extras
  UPLOADED: { label: 'Uploaded', tone: 'neutral' },
  VERIFIED: { label: 'Verified', tone: 'success' },
  DRAFT: { label: 'Draft', tone: 'neutral' },
  // ApprovalStatus extras
  PENDING: { label: 'Pending', tone: 'warning' },
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
