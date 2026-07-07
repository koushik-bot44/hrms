import type { Metadata } from 'next';
import { PageHeader } from '@/components/page-header';
import { RoleDashboard } from '@/components/dashboard/role-dashboard';
import { ApprovedEmployeesTable } from '@/components/accountant/approved-employees-table';

export const metadata: Metadata = { title: 'Overview' };

export default function AccountantOverviewPage() {
  return (
    <div className="space-y-6">
      <PageHeader
        title="Accountant workspace"
        description="Read-only oversight across every company — approved employees and their records."
      />
      <RoleDashboard show="stats" />
      <h2 className="text-sm font-semibold text-muted-foreground">Approved employees</h2>
      <ApprovedEmployeesTable />
    </div>
  );
}
