'use client';

import type { ColumnDef } from '@tanstack/react-table';
import { UserPlus, Users } from 'lucide-react';
import type { EmployeeStatus } from '@ihrms/shared';
import { PageHeader } from '@/components/page-header';
import { DataTable } from '@/components/data-table';
import { EmptyState } from '@/components/empty-state';
import { StatusBadge } from '@/components/status-badge';
import { Button } from '@/components/ui/button';

interface EmployeeRow {
  id: string;
  employeeCode: string;
  email: string;
  status: EmployeeStatus;
}

const columns: ColumnDef<EmployeeRow>[] = [
  {
    accessorKey: 'employeeCode',
    header: 'Employee ID',
    cell: ({ row }) => <span className="font-mono text-xs">{row.original.employeeCode}</span>,
  },
  { accessorKey: 'email', header: 'Email' },
  {
    accessorKey: 'status',
    header: 'Status',
    enableSorting: false,
    cell: ({ row }) => <StatusBadge status={row.original.status} />,
  },
];

export default function HrEmployeesPage() {
  const data: EmployeeRow[] = []; // wired to the API in a later phase

  return (
    <div className="space-y-6">
      <PageHeader
        title="Employees"
        description="Look up an employee by ID and review their record."
        actions={
          <Button size="sm">
            <UserPlus />
            Onboard
          </Button>
        }
      />
      <DataTable
        columns={columns}
        data={data}
        searchPlaceholder="Search by ID or email…"
        emptyState={
          <EmptyState
            icon={Users}
            title="No employees yet"
            description="Onboard an employee to mint their unique ID and start their record."
            action={
              <Button size="sm">
                <UserPlus />
                Onboard employee
              </Button>
            }
          />
        }
      />
    </div>
  );
}
