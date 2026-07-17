import type { Metadata } from 'next';
import { PageHeader } from '@/components/page-header';
import { RoleDashboard } from '@/components/dashboard/role-dashboard';
import { ViewerEmployeesBrowser } from '@/components/accountant/viewer-employees-browser';

export const metadata: Metadata = { title: 'Overview' };

export default function AccountantOverviewPage() {
  return (
    <div className="space-y-6">
      <PageHeader
        title="Accounts workspace"
        description="Read-only oversight of approved employees and their records, scoped to your access."
      />
      <RoleDashboard show="stats" />
      <ViewerEmployeesBrowser />
    </div>
  );
}
