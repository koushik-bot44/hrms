import type { Metadata } from 'next';
import { ClipboardCheck } from 'lucide-react';
import { PageHeader } from '@/components/page-header';
import { EmptyState } from '@/components/empty-state';

export const metadata: Metadata = { title: 'Approvals' };

export default function ManagerApprovalsPage() {
  return (
    <div className="space-y-6">
      <PageHeader
        title="Approvals"
        description="Verified employees awaiting your final approval."
      />
      <EmptyState
        icon={ClipboardCheck}
        title="No pending approvals"
        description="When HR routes a verified employee to you, it shows up here to approve."
      />
    </div>
  );
}
