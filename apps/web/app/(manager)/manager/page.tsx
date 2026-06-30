import type { Metadata } from 'next';
import { PageHeader } from '@/components/page-header';
import { ApprovalsInbox } from '@/components/manager/approvals-inbox';

export const metadata: Metadata = { title: 'Approvals' };

export default function ManagerApprovalsPage() {
  return (
    <div className="space-y-6">
      <PageHeader
        title="Approvals"
        description="Verified employees on your team awaiting your final approval."
      />
      <ApprovalsInbox />
    </div>
  );
}
