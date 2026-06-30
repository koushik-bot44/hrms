'use client';

import type { ColumnDef } from '@tanstack/react-table';
import { Users } from 'lucide-react';
import type { EmployeeSummary } from '@/lib/contract';
import { listMyEmployees } from '@/lib/api/employees';
import { useApiQuery } from '@/lib/api/hooks';
import { PageHeader } from '@/components/page-header';
import { DataTable } from '@/components/data-table';
import { EmptyState } from '@/components/empty-state';
import { TableSkeleton } from '@/components/loading-skeleton';
import { StatusBadge } from '@/components/status-badge';
import { OnboardEmployeeDialog } from '@/components/hr/onboard-employee-dialog';

const columns: ColumnDef<EmployeeSummary>[] = [
  {
    accessorKey: 'fullName',
    header: 'Name',
    cell: ({ row }) => <span className="font-medium">{row.original.fullName ?? '—'}</span>,
  },
  { accessorKey: 'email', header: 'Email' },
  {
    accessorKey: 'designation',
    header: 'Designation',
    cell: ({ row }) => (
      <span className="text-muted-foreground">{row.original.designation ?? '—'}</span>
    ),
  },
  {
    accessorKey: 'employeeCode',
    header: 'Employee ID',
    cell: ({ row }) =>
      row.original.employeeCode ? (
        <span className="font-mono text-xs">{row.original.employeeCode}</span>
      ) : (
        <span className="text-xs text-muted-foreground">— on approval</span>
      ),
  },
  {
    accessorKey: 'status',
    header: 'Status',
    enableSorting: false,
    cell: ({ row }) => <StatusBadge status={row.original.status} />,
  },
  {
    accessorKey: 'createdAt',
    header: 'Onboarded',
    cell: ({ row }) => (
      <span className="text-muted-foreground">
        {new Date(row.original.createdAt).toLocaleDateString()}
      </span>
    ),
  },
];

export default function HrEmployeesPage() {
  const { data, isLoading, isError, error } = useApiQuery(['hr-employees'], listMyEmployees);

  return (
    <div className="space-y-6">
      <PageHeader
        title="Employees"
        description="Onboard a new employee and track those you've onboarded."
        actions={<OnboardEmployeeDialog />}
      />

      {isLoading ? (
        <TableSkeleton rows={5} cols={4} />
      ) : isError ? (
        <EmptyState
          icon={Users}
          title="Couldn't load employees"
          description={error?.message ?? 'Please try again.'}
        />
      ) : (
        <DataTable
          columns={columns}
          data={data ?? []}
          searchPlaceholder="Search by name or email…"
          emptyState={
            <EmptyState
              icon={Users}
              title="No employees yet"
              description="Onboard an employee to mint their unique ID and start their record."
              action={<OnboardEmployeeDialog />}
            />
          }
        />
      )}
    </div>
  );
}
