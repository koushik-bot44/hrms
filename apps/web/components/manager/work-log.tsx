'use client';

import { Users } from 'lucide-react';
import { getMyManagerTeam } from '@/lib/api/manager';
import { useApiQuery } from '@/lib/api/hooks';
import { EmptyState } from '@/components/empty-state';
import { LoadingSkeleton } from '@/components/loading-skeleton';
import { TeamAttendance } from '@/components/accountant/team-attendance';

/**
 * The Manager's attendance ANALYTICS (§8a) — the SAME shared viewer dashboard the Accountant uses (team
 * summary tiles + roster → employee work-log detail with donut/tiles/monthly series/CSV + month or custom
 * range), scoped to the Manager's OWN team. A thin mount: it only resolves the manager's teamId and hands
 * it to the shared component; the API enforces own-team scope (foreign → 404). No screen code is duplicated.
 */
export function ManagerWorkLog() {
  const query = useApiQuery(['manager-my-team'], (signal) => getMyManagerTeam(signal));

  if (query.isLoading) return <LoadingSkeleton lines={4} />;
  if (query.isError) {
    return (
      <EmptyState
        icon={Users}
        title="Couldn't load your team"
        description={query.error?.message ?? 'Please try again.'}
      />
    );
  }
  const team = query.data;
  if (!team) {
    return (
      <EmptyState
        icon={Users}
        title="No team assigned"
        description="You aren't the manager of any team yet. Ask your Company Admin to assign you."
      />
    );
  }

  return <TeamAttendance teamId={team.teamId} />;
}
