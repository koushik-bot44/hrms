'use client';

import * as React from 'react';
import Link from 'next/link';
import { useRouter, useSearchParams } from 'next/navigation';
import type { ColumnDef } from '@tanstack/react-table';
import { Archive, Building2, MoreHorizontal, RotateCcw, Trash2, XOctagon } from 'lucide-react';
import type { CompanySummary } from '@/lib/contract';
import { listCompanies, listDeletedCompanies } from '@/lib/api/companies';
import { useApiQuery } from '@/lib/api/hooks';
import { PageHeader } from '@/components/page-header';
import { DataTable } from '@/components/data-table';
import { EmptyState } from '@/components/empty-state';
import { TableSkeleton } from '@/components/loading-skeleton';
import { CreateCompanyDialog } from '@/components/super-admin/create-company-dialog';
import { CreateAccountantDialog } from '@/components/super-admin/create-accountant-dialog';
import { CreateHierarchyDialog } from '@/components/super-admin/create-hierarchy-dialog';
import { SuperAdminOnboardDialog } from '@/components/super-admin/super-admin-onboard-dialog';
import { CompanyStatusBadge } from '@/components/super-admin/company-status-badge';
import { DeleteCompanyDialog } from '@/components/super-admin/delete-company-dialog';
import { RestoreCompanyDialog } from '@/components/super-admin/restore-company-dialog';
import { PurgeCompanyDialog } from '@/components/super-admin/purge-company-dialog';
import { RoleDashboard } from '@/components/dashboard/role-dashboard';
import { TodayChip } from '@/components/accountant/today-chip';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { cn } from '@/lib/utils';

type View = 'active' | 'archived';

export default function CompaniesPage() {
  // useSearchParams() needs a Suspense boundary on this statically-rendered route.
  return (
    <React.Suspense fallback={null}>
      <CompaniesView />
    </React.Suspense>
  );
}

function CompaniesView() {
  const router = useRouter();
  const searchParams = useSearchParams();
  // The URL query is the source of truth for the tab, so a dashboard drill-down (?view=archived) switches
  // the view via SOFT navigation too — the old mount-only effect never re-ran on client navigation, so
  // clicking the stat cards changed nothing.
  const view: View = searchParams.get('view') === 'archived' ? 'archived' : 'active';
  const setView = (next: View) =>
    router.replace(next === 'archived' ? '/super-admin?view=archived' : '/super-admin', {
      scroll: false,
    });

  const [deleting, setDeleting] = React.useState<CompanySummary | null>(null);
  const [restoring, setRestoring] = React.useState<CompanySummary | null>(null);
  const [purging, setPurging] = React.useState<CompanySummary | null>(null);

  const active = useApiQuery(['companies'], listCompanies, { enabled: view === 'active' });
  const archived = useApiQuery(['companies', 'deleted'], listDeletedCompanies, {
    enabled: view === 'archived',
  });
  const query = view === 'active' ? active : archived;

  const columns = React.useMemo<ColumnDef<CompanySummary>[]>(
    () => [
      {
        accessorKey: 'name',
        header: 'Name',
        cell: ({ row }) => (
          <Link
            href={`/super-admin/companies/${row.original.id}`}
            className="font-medium text-foreground hover:text-primary hover:underline"
          >
            {row.original.name}
          </Link>
        ),
      },
      {
        accessorKey: 'code',
        header: 'Code',
        cell: ({ row }) => <span className="font-mono text-xs">{row.original.code}</span>,
      },
      { accessorKey: 'teamCount', header: 'Teams' },
      { accessorKey: 'employeeCount', header: 'Employees' },
      {
        id: 'admin',
        header: 'Admin',
        enableSorting: false,
        cell: ({ row }) =>
          row.original.hasAdmin ? (
            <Badge variant="primarySoft">Provisioned</Badge>
          ) : (
            <Badge variant="neutral">None</Badge>
          ),
      },
      {
        accessorKey: 'status',
        header: 'Status',
        enableSorting: false,
        cell: ({ row }) => <CompanyStatusBadge status={row.original.status} />,
      },
      {
        id: 'actions',
        header: '',
        enableSorting: false,
        cell: ({ row }) => (
          <div className="text-right">
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button variant="ghost" size="icon" aria-label="Company actions">
                  <MoreHorizontal />
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end">
                <DropdownMenuItem asChild>
                  <Link href={`/super-admin/companies/${row.original.id}`}>View details</Link>
                </DropdownMenuItem>
                {view === 'active' ? (
                  <DropdownMenuItem
                    className="text-destructive focus:text-destructive"
                    onSelect={() => setDeleting(row.original)}
                  >
                    <Trash2 className="size-4" />
                    Archive company
                  </DropdownMenuItem>
                ) : (
                  <DropdownMenuItem onSelect={() => setRestoring(row.original)}>
                    <RotateCcw className="size-4" />
                    Restore company
                  </DropdownMenuItem>
                )}
                <DropdownMenuSeparator />
                <DropdownMenuItem
                  className="text-destructive focus:text-destructive"
                  onSelect={() => setPurging(row.original)}
                >
                  <XOctagon className="size-4" />
                  Permanently delete
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </div>
        ),
      },
    ],
    [view],
  );

  return (
    <div className="space-y-8">
      <PageHeader
        title="Companies"
        description="Every company in the portal. Provision each company's admin, or archive one."
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <TodayChip />
            <CreateAccountantDialog />
            <CreateHierarchyDialog />
            <SuperAdminOnboardDialog />
            <CreateCompanyDialog />
          </div>
        }
        editorial
      />

      <RoleDashboard show="stats" heroStats />

      <h2 className="text-sm font-semibold text-muted-foreground">Companies</h2>

      <div className="flex gap-2">
        <ViewTab active={view === 'active'} onClick={() => setView('active')} icon={Building2}>
          Active
        </ViewTab>
        <ViewTab active={view === 'archived'} onClick={() => setView('archived')} icon={Archive}>
          Archived
        </ViewTab>
      </div>

      {query.isLoading ? (
        <TableSkeleton rows={5} cols={6} />
      ) : query.isError ? (
        <EmptyState
          icon={Building2}
          title="Couldn't load companies"
          description={query.error?.message ?? 'Please try again.'}
        />
      ) : (
        <DataTable carded
          columns={columns}
          data={query.data ?? []}
          searchPlaceholder="Search by name or code…"
          emptyState={
            <EmptyState
              icon={view === 'active' ? Building2 : Archive}
              title={view === 'active' ? 'No companies yet' : 'No archived companies'}
              description={
                view === 'active'
                  ? 'Create the first company to start provisioning admins and teams.'
                  : 'Archived companies will appear here and can be restored.'
              }
              action={view === 'active' ? <CreateCompanyDialog /> : undefined}
            />
          }
        />
      )}

      <RoleDashboard show="activity" />

      <DeleteCompanyDialog company={deleting} onClose={() => setDeleting(null)} />
      <RestoreCompanyDialog company={restoring} onClose={() => setRestoring(null)} />
      <PurgeCompanyDialog company={purging} onClose={() => setPurging(null)} />
    </div>
  );
}

function ViewTab({
  active,
  onClick,
  icon: Icon,
  children,
}: {
  active: boolean;
  onClick: () => void;
  icon: React.ComponentType<{ className?: string }>;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={cn(
        'flex items-center gap-2 rounded-md border px-3 py-1.5 text-sm font-medium transition-colors',
        active ? 'border-primary bg-primary text-primary-foreground' : 'border-border hover:border-primary/50',
      )}
    >
      <Icon className="size-4" />
      {children}
    </button>
  );
}
