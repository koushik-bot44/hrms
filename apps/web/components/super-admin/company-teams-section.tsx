'use client';

import Link from 'next/link';
import { Users } from 'lucide-react';
import { listTeams, teamsKey } from '@/lib/api/teams';
import { useApiQuery } from '@/lib/api/hooks';
import { Skeleton } from '@/components/ui/skeleton';
import { EmptyState } from '@/components/empty-state';
import { TeamStatusBadge } from '@/components/company-admin/team-status-badge';
import { CreateTeamDialog } from '@/components/company-admin/create-team-dialog';

/** Super Admin's teams management for one company: list + create, each row drilling into the team. */
export function CompanyTeamsSection({ companyId }: { companyId: string }) {
  const query = useApiQuery(teamsKey(companyId), (signal) => listTeams(companyId, signal));

  return (
    <section className="space-y-3">
      <div className="flex items-center justify-between">
        <h2 className="text-sm font-semibold text-muted-foreground">Teams</h2>
        <CreateTeamDialog companyId={companyId} />
      </div>

      {query.isLoading ? (
        <div className="space-y-2">
          <Skeleton className="h-12 w-full" />
          <Skeleton className="h-12 w-full" />
        </div>
      ) : query.isError ? (
        <EmptyState
          icon={Users}
          title="Couldn't load teams"
          description={query.error?.message ?? 'Please try again.'}
        />
      ) : !query.data || query.data.length === 0 ? (
        <EmptyState
          icon={Users}
          title="No teams yet"
          description="Create a team and assign its HR and Manager to start onboarding."
          action={<CreateTeamDialog companyId={companyId} />}
        />
      ) : (
        <div className="overflow-x-auto rounded-md border">
          <table className="w-full text-sm">
            <thead className="bg-muted/40 text-left text-xs text-muted-foreground">
              <tr>
                <th className="px-3 py-2 font-medium">Team</th>
                <th className="px-3 py-2 font-medium">HR</th>
                <th className="px-3 py-2 font-medium">Manager</th>
                <th className="px-3 py-2 font-medium">Members</th>
                <th className="px-3 py-2 font-medium">Status</th>
              </tr>
            </thead>
            <tbody>
              {query.data.map((team) => (
                <tr key={team.id} className="border-t hover:bg-accent/40">
                  <td className="px-3 py-2 font-medium">
                    <Link
                      href={`/super-admin/companies/${companyId}/teams/${team.id}`}
                      className="text-primary hover:underline"
                    >
                      {team.name}
                    </Link>
                  </td>
                  <td className="px-3 py-2 text-muted-foreground">{team.hr?.name ?? 'Unassigned'}</td>
                  <td className="px-3 py-2 text-muted-foreground">
                    {team.manager?.name ?? 'Unassigned'}
                  </td>
                  <td className="px-3 py-2 tabular-nums">{team.memberCount}</td>
                  <td className="px-3 py-2">
                    <TeamStatusBadge complete={Boolean(team.hr && team.manager)} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
