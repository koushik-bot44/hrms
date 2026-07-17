'use client';

import { Users } from 'lucide-react';
import { getMyTeam } from '@/lib/api/accountant';
import { useApiQuery } from '@/lib/api/hooks';
import { EmptyState } from '@/components/empty-state';
import { LoadingSkeleton } from '@/components/loading-skeleton';
import { TeamViewTabs } from '@/components/accountant/team-view-tabs';

/**
 * The ACCOUNTANT lands directly on THEIR OWN team's roster (§2) — no company/team pickers (the role has
 * exactly one team). Read-only. The header names the team; the table is scoped server-side to that team.
 */
export function AccountantTeamRoster() {
  const query = useApiQuery(['accountant-my-team'], (signal) => getMyTeam(signal));

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
        description="You aren't the accountant of any team yet. Ask your Company Admin to assign you."
      />
    );
  }

  return (
    <div className="space-y-4">
      <div className="rounded-md border bg-muted/30 px-4 py-3 text-sm">
        <span className="font-medium text-foreground">{team.teamName}</span>
        <span className="text-muted-foreground">
          {' '}
          · {team.companyName ?? '—'} · HR {team.hrName ?? '—'} · Manager {team.managerName ?? '—'}
        </span>
      </div>
      <TeamViewTabs teamId={team.teamId} />
    </div>
  );
}
