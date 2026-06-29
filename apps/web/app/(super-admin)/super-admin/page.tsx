'use client';

import Link from 'next/link';
import type { ColumnDef } from '@tanstack/react-table';
import { Building2, MoreHorizontal } from 'lucide-react';
import type { CompanySummary } from '@/lib/contract';
import { listCompanies } from '@/lib/api/companies';
import { useApiQuery } from '@/lib/api/hooks';
import { PageHeader } from '@/components/page-header';
import { DataTable } from '@/components/data-table';
import { EmptyState } from '@/components/empty-state';
import { TableSkeleton } from '@/components/loading-skeleton';
import { CreateCompanyDialog } from '@/components/super-admin/create-company-dialog';
import { CompanyStatusBadge } from '@/components/super-admin/company-status-badge';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';

const columns: ColumnDef<CompanySummary>[] = [
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
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
    ),
  },
];

export default function CompaniesPage() {
  const { data, isLoading, isError, error } = useApiQuery(['companies'], listCompanies);

  return (
    <div className="space-y-6">
      <PageHeader
        title="Companies"
        description="Every company in the portal. Provision each company's admin."
        actions={<CreateCompanyDialog />}
      />

      {isLoading ? (
        <TableSkeleton rows={5} cols={6} />
      ) : isError ? (
        <EmptyState
          icon={Building2}
          title="Couldn't load companies"
          description={error?.message ?? 'Please try again.'}
        />
      ) : (
        <DataTable
          columns={columns}
          data={data ?? []}
          searchPlaceholder="Search by name or code…"
          emptyState={
            <EmptyState
              icon={Building2}
              title="No companies yet"
              description="Create the first company to start provisioning admins and teams."
              action={<CreateCompanyDialog />}
            />
          }
        />
      )}
    </div>
  );
}
