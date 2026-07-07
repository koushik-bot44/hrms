import type { Metadata } from 'next';
import { PageHeader } from '@/components/page-header';
import { ApprovalAudit } from '@/components/accountant/approval-audit';

export const metadata: Metadata = { title: 'Approval audit' };

export default function AccountantAuditPage() {
  return (
    <div className="space-y-6">
      <PageHeader
        title="Approval audit"
        description="Approval decisions across every company — the only audit trail the Accountant can read."
      />
      <ApprovalAudit />
    </div>
  );
}
