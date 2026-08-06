import type { Metadata } from 'next';
import { PageHeader } from '@/components/page-header';
import { AuditExplorer } from '@/components/audit/audit-explorer';

export const metadata: Metadata = { title: 'Audit logs' };

export default function CompanyAdminAuditPage() {
  return (
    <div className="space-y-6">
      <PageHeader title="Audit logs" description="Every logged action within your company." />
      <AuditExplorer scope="company" />
    </div>
  );
}
