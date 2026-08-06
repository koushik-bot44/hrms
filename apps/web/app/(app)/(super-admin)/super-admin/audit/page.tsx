import type { Metadata } from 'next';
import { PageHeader } from '@/components/page-header';
import { AuditExplorer } from '@/components/audit/audit-explorer';

export const metadata: Metadata = { title: 'Audit logs' };

export default function SuperAdminAuditPage() {
  return (
    <div className="space-y-6">
      <PageHeader
        title="Audit logs"
        description="Every company's audit trail, kept separate per company. Pick a company to explore it."
      />
      <AuditExplorer scope="super" />
    </div>
  );
}
