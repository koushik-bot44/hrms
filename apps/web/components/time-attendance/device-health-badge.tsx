'use client';

import { Badge } from '@/components/ui/badge';

/**
 * Terminal health, expressed as time-since-contact rather than as a status word.
 *
 * A device that has not been heard from is the single failure that silently stops attendance, and it
 * looks identical to "nobody has punched yet" unless the UI says which it is. The eSSL fleet pushes a
 * heartbeat on a fixed interval, so silence is measurable: the badge reports the gap in the operator's
 * units ("14m ago"), and `healthy` — computed server-side against the same interval — decides the tone.
 */
export function DeviceHealthBadge({
  healthy,
  minutesSinceSeen,
  lastSeenAt,
  status,
}: {
  healthy: boolean;
  minutesSinceSeen: number;
  lastSeenAt: string | null;
  status?: string;
}) {
  if (status && status !== 'CLAIMED') {
    return <Badge variant="neutral">Unclaimed</Badge>;
  }
  if (!lastSeenAt) {
    return (
      <Badge variant="danger" title="This terminal has never contacted the server.">
        Never seen
      </Badge>
    );
  }
  return (
    <Badge
      variant={healthy ? 'success' : 'danger'}
      title={healthy ? 'Heartbeat is current.' : 'No heartbeat — punches may not be arriving.'}
    >
      {healthy ? 'Online' : 'Silent'} · {formatGap(minutesSinceSeen)}
    </Badge>
  );
}

/** Minutes-since-contact in the units an operator thinks in. */
export function formatGap(minutes: number): string {
  const m = Math.max(0, Math.floor(minutes));
  if (m < 1) return 'just now';
  if (m < 60) return `${m}m ago`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}h ago`;
  return `${Math.floor(h / 24)}d ago`;
}
