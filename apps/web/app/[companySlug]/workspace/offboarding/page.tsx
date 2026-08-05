import type { Metadata } from 'next';
import { PageHeader } from '@/components/page-header';
import { OffboardingDocList } from '@/components/employee/offboarding-doc-list';

export const metadata: Metadata = { title: 'Offboarding' };

/** The workspace Offboarding section (§3.6 stage 2): the employee's documents to read and sign. */
export default function WorkspaceOffboardingPage() {
  return (
    <div className="space-y-6">
      <PageHeader
        title="Offboarding"
        description="Read and sign your offboarding documents. Completed documents stay here to download."
        editorial
      />
      <OffboardingDocList />
    </div>
  );
}
