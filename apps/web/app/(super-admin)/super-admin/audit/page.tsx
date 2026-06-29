import type { Metadata } from 'next';
import { ScrollText } from 'lucide-react';
import { PageHeader } from '@/components/page-header';
import { EmptyState } from '@/components/empty-state';

export const metadata: Metadata = { title: 'Audit logs' };

export default function SuperAdminAuditPage() {
  return (
    <div className="space-y-6">
      <PageHeader
        title="Audit logs"
        description="Every company's audit trail, partitioned per company."
      />
      <EmptyState
        icon={ScrollText}
        title="The audit explorer arrives in a later phase"
        description="Filter, sort and search every logged action across all companies, separated per company."
      />
    </div>
  );
}
