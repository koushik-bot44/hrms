import type { Metadata } from 'next';
import { PageHeader } from '@/components/page-header';
import { ApprovalsWorkspace } from '@/components/manager/approvals-workspace';

export const metadata: Metadata = { title: 'Approvals' };

export default function ManagerApprovalsPage() {
  return (
    <div className="space-y-6">
      <PageHeader
        title="Approvals"
        description="Approve verified employees on your team, and review your past decisions."
      />
      <ApprovalsWorkspace />
    </div>
  );
}
