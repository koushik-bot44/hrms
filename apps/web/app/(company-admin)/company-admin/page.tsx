'use client';

import Link from 'next/link';
import type { ColumnDef } from '@tanstack/react-table';
import { MoreHorizontal, Users } from 'lucide-react';
import type { TeamSummary } from '@ihrms/shared';
import { listTeams } from '@/lib/api/teams';
import { useApiQuery } from '@/lib/api/hooks';
import { PageHeader } from '@/components/page-header';
import { DataTable } from '@/components/data-table';
import { EmptyState } from '@/components/empty-state';
import { TableSkeleton } from '@/components/loading-skeleton';
import { CreateTeamDialog } from '@/components/company-admin/create-team-dialog';
import { TeamStatusBadge } from '@/components/company-admin/team-status-badge';
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

const columns: ColumnDef<TeamSummary>[] = [
  {
    accessorKey: 'name',
    header: 'Team',
    cell: ({ row }) => (
      <Link
        href={`/company-admin/teams/${row.original.id}`}
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
  { accessorKey: 'memberCount', header: 'Members' },
  {
    id: 'status',
    header: 'Status',
    enableSorting: false,
    cell: ({ row }) => (
      <TeamStatusBadge complete={Boolean(row.original.hr && row.original.manager)} />
    ),
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
              <Link href={`/company-admin/teams/${row.original.id}`}>View details</Link>
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
    ),
  },
];

export default function TeamsPage() {
  const { data, isLoading, isError, error } = useApiQuery(['teams'], listTeams);

  return (
    <div className="space-y-6">
      <PageHeader
        title="Teams"
        description="Create teams and assign one HR and one Manager to each."
        actions={<CreateTeamDialog />}
      />

      {isLoading ? (
        <TableSkeleton rows={5} cols={5} />
      ) : isError ? (
        <EmptyState
          icon={Users}
          title="Couldn't load teams"
          description={error?.message ?? 'Please try again.'}
        />
      ) : (
        <DataTable
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
    </div>
  );
}
