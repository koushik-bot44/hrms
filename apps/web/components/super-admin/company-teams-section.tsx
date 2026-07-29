'use client';

import Link from 'next/link';
import { Users } from 'lucide-react';
import { listTeams, teamsKey } from '@/lib/api/teams';
import { useApiQuery } from '@/lib/api/hooks';
import { companyParam } from '@/lib/company-url';
import { TableSkeleton } from '@/components/loading-skeleton';
import { EmptyState } from '@/components/empty-state';
import { TeamStatusBadge } from '@/components/company-admin/team-status-badge';
import { CreateTeamDialog } from '@/components/company-admin/create-team-dialog';

/** Super Admin's teams management for one company: list + create, each row drilling into the team. */
export function CompanyTeamsSection({ companyId, companyName }: { companyId: string; companyName: string }) {
  const query = useApiQuery(teamsKey(companyId), (signal) => listTeams(companyId, signal));

  return (
    <section className="space-y-3">
      <div className="flex items-center justify-between">
        <h2 className="text-sm font-semibold text-muted-foreground">Teams</h2>
        <CreateTeamDialog companyId={companyId} />
      </div>

      {query.isLoading ? (
        <TableSkeleton rows={4} cols={6} />
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
        <div className="overflow-hidden rounded-2xl border bg-card shadow-card">
          <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="bg-muted/40 text-left text-xs text-muted-foreground">
              <tr>
                <th className="px-4 py-3 font-medium">Team</th>
                <th className="px-4 py-3 font-medium">HR</th>
                <th className="px-4 py-3 font-medium">Manager</th>
                <th className="px-4 py-3 font-medium">Accountant</th>
                <th className="px-4 py-3 font-medium">Members</th>
                <th className="px-4 py-3 font-medium">Status</th>
              </tr>
            </thead>
            <tbody>
              {query.data.map((team) => (
                <tr key={team.id} className="border-t hover:bg-accent/40">
                  <td className="px-4 py-3 font-medium">
                    <Link
                      href={`/super-admin/companies/${companyParam(companyName, companyId)}/teams/${team.id}`}
                      className="text-primary hover:underline"
                    >
                      {team.name}
                    </Link>
                  </td>
                  <td className="px-4 py-3 text-muted-foreground">{team.hr?.name ?? 'Unassigned'}</td>
                  <td className="px-4 py-3 text-muted-foreground">
                    {team.manager?.name ?? 'Unassigned'}
                  </td>
                  <td className="px-4 py-3 text-muted-foreground">
                    {team.accountant?.name ?? 'Unassigned'}
                  </td>
                  <td className="px-4 py-3 tabular-nums">{team.memberCount}</td>
                  <td className="px-4 py-3">
                    <TeamStatusBadge complete={team.complete} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          </div>
        </div>
      )}
    </section>
  );
}
