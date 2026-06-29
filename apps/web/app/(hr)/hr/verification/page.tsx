import type { Metadata } from 'next';
import { ShieldCheck } from 'lucide-react';
import { PageHeader } from '@/components/page-header';
import { EmptyState } from '@/components/empty-state';

export const metadata: Metadata = { title: 'Verification' };

export default function HrVerificationPage() {
  return (
    <div className="space-y-6">
      <PageHeader
        title="Verification"
        description="Review submitted sections and documents, then route to the Manager."
      />
      <EmptyState
        icon={ShieldCheck}
        title="Nothing to verify"
        description="Employees who have submitted their record for verification will appear here."
      />
    </div>
  );
}
