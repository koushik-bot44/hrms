import type { Metadata } from 'next';
import { PageHeader } from '@/components/page-header';
import { VerificationWorkspace } from '@/components/hr/verification-workspace';

export const metadata: Metadata = { title: 'Verification' };

export default function HrVerificationPage() {
  return (
    <div className="space-y-6">
      <PageHeader
        title="Verification"
        description="Look up an employee by ID, review their submitted sections and documents, then route to the Manager."
      />
      <VerificationWorkspace />
    </div>
  );
}
