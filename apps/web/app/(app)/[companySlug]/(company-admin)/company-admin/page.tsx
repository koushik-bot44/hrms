'use client';

import * as React from 'react';
import Link from 'next/link';
import type { ColumnDef } from '@tanstack/react-table';
import { MoreHorizontal, Users } from 'lucide-react';
import type { TeamSummary } from '@/lib/contract';
import { listTeams } from '@/lib/api/teams';
import { useApiQuery } from '@/lib/api/hooks';
import { useCompanyPath } from '@/lib/auth/use-company-path';
import { PageHeader } from '@/components/page-header';
import { DataTable } from '@/components/data-table';
import { EmptyState } from '@/components/empty-state';
import { TableSkeleton } from '@/components/loading-skeleton';
import { CreateTeamDialog } from '@/components/company-admin/create-team-dialog';
import { TeamStatusBadge } from '@/components/company-admin/team-status-badge';
import { RoleDashboard } from '@/components/dashboard/role-dashboard';
import { TodayChip } from '@/components/accountant/today-chip';
import { Button } from '@/components/ui/button';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';

function Assignee({ name }: { name: string | undefined }) {
  return name ? (
    <span>{name}</span>
  ) : (
    <span className="text-muted-foreground">Unassigned</span>
  );
}

const columnsFor = (cp: (subpath?: string) => string): ColumnDef<TeamSummary>[] => [
  {
    accessorKey: 'name',
    header: 'Team',
    cell: ({ row }) => (
      <Link
        href={cp(`/company-admin/teams/${row.original.id}`)}
        className="font-medium text-foreground hover:text-primary hover:underline"
      >
        {row.original.name}
      </Link>
    ),
  },
  {
    id: 'hr',
    header: 'HR',
    enableSorting: false,
    cell: ({ row }) => <Assignee name={row.original.hr?.name} />,
  },
  {
    id: 'manager',
    header: 'Manager',
    enableSorting: false,
    cell: ({ row }) => <Assignee name={row.original.manager?.name} />,
  },
  {
    id: 'accountant',
    header: 'Accountant',
    enableSorting: false,
    cell: ({ row }) => <Assignee name={row.original.accountant?.name} />,
  },
  { accessorKey: 'memberCount', header: 'Members' },
  {
    id: 'status',
    header: 'Status',
    enableSorting: false,
    cell: ({ row }) => <TeamStatusBadge complete={row.original.complete} />,
  },
  {
    id: 'actions',
    header: '',
    enableSorting: false,
    cell: ({ row }) => (
      <div className="text-right">
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="ghost" size="icon" aria-label="Team actions">
              <MoreHorizontal />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end">
            <DropdownMenuItem asChild>
              <Link href={cp(`/company-admin/teams/${row.original.id}`)}>View details</Link>
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
    ),
  },
];

export default function TeamsPage() {
  const { data, isLoading, isError, error } = useApiQuery(['teams'], (signal) =>
    listTeams(undefined, signal),
  );
  const cp = useCompanyPath();
  const columns = React.useMemo(() => columnsFor(cp), [cp]);

  return (
    <div className="space-y-8">
      <PageHeader
        title="Company workspace"
        description="Your company at a glance — teams, onboarding progress, and recent activity."
        actions={
          <>
            <TodayChip />
            <CreateTeamDialog />
          </>
        }
        editorial
      />

      {/* Stats up top, the Teams block next, then the activity feed below — both dashboard sections
          share the one deduped ['dashboard'] query. */}
      <RoleDashboard show="stats" />

      <h2 id="teams" className="scroll-mt-24 text-sm font-semibold text-muted-foreground">Teams</h2>

      {isLoading ? (
        <TableSkeleton rows={5} cols={5} />
      ) : isError ? (
        <EmptyState
          icon={Users}
          title="Couldn't load teams"
          description={error?.message ?? 'Please try again.'}
        />
      ) : (
        <DataTable carded
          columns={columns}
          data={data ?? []}
          searchPlaceholder="Search teams…"
          emptyState={
            <EmptyState
              icon={Users}
              title="No teams yet"
              description="Create a team and assign its HR and Manager to begin onboarding."
              action={<CreateTeamDialog />}
            />
          }
        />
      )}

      <RoleDashboard show="activity" />
    </div>
  );
}
