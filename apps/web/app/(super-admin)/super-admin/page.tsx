'use client';

import type { ColumnDef } from '@tanstack/react-table';
import { Building2, Plus } from 'lucide-react';
import { PageHeader } from '@/components/page-header';
import { DataTable } from '@/components/data-table';
import { EmptyState } from '@/components/empty-state';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';

interface CompanyRow {
  id: string;
  name: string;
  code: string;
  teams: number;
}

const columns: ColumnDef<CompanyRow>[] = [
  { accessorKey: 'name', header: 'Name' },
  { accessorKey: 'code', header: 'Code', cell: ({ row }) => <span className="font-mono text-xs">{row.original.code}</span> },
  { accessorKey: 'teams', header: 'Teams' },
  { id: 'status', header: 'Status', enableSorting: false, cell: () => <Badge variant="outline">Active</Badge> },
];

export default function CompaniesPage() {
  const data: CompanyRow[] = []; // wired to the API in a later phase

  return (
    <div className="space-y-6">
      <PageHeader
        title="Companies"
        description="Every company in the portal. Provision each company's admin."
        actions={
          <Button size="sm">
            <Plus />
            New company
          </Button>
        }
      />
      <DataTable
        columns={columns}
        data={data}
        searchPlaceholder="Search companies…"
        emptyState={
          <EmptyState
            icon={Building2}
            title="No companies yet"
            description="Create the first company to start provisioning admins and teams."
            action={
              <Button size="sm">
                <Plus />
                New company
              </Button>
            }
          />
        }
      />
    </div>
  );
}
