import type { Metadata } from 'next';
import { PageHeader } from '@/components/page-header';
import { OffboardingDocList } from '@/components/employee/offboarding-doc-list';
import { OffboardingLetters } from '@/components/employee/offboarding-letters';

export const metadata: Metadata = { title: 'Offboarding' };

/** The workspace Offboarding section (§3.6): the employee's documents to sign + the letters to request. */
export default function WorkspaceOffboardingPage() {
  return (
    <div className="space-y-6">
      <PageHeader
        title="Offboarding"
        description="Read and sign your offboarding documents, then request your letters."
        editorial
      />
      <OffboardingDocList />
      <OffboardingLetters />
    </div>
  );
}
