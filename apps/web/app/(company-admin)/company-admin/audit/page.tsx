import type { Metadata } from 'next';
import { ScrollText } from 'lucide-react';
import { PageHeader } from '@/components/page-header';
import { EmptyState } from '@/components/empty-state';

export const metadata: Metadata = { title: 'Audit logs' };

export default function CompanyAdminAuditPage() {
  return (
    <div className="space-y-6">
      <PageHeader title="Audit logs" description="Your company's audit trail." />
      <EmptyState
        icon={ScrollText}
        title="The audit view arrives in a later phase"
        description="Review every logged action within your company — filterable and sortable."
      />
    </div>
  );
}
