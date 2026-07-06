import type { Metadata } from 'next';
import { PageHeader } from '@/components/page-header';
import { ApprovalsWorkspace } from '@/components/manager/approvals-workspace';
import { RoleDashboard } from '@/components/dashboard/role-dashboard';

export const metadata: Metadata = { title: 'Dashboard' };

export default function ManagerApprovalsPage() {
  return (
    <div className="space-y-6">
      <PageHeader
        title="Manager workspace"
        description="Your team at a glance — approve verified employees and review past decisions."
      />
      <RoleDashboard />
      <ApprovalsWorkspace />
    </div>
  );
}
