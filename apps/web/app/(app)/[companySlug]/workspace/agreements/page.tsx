import type { Metadata } from 'next';
import { PageHeader } from '@/components/page-header';
import { AgreementsList } from '@/components/employee/agreements-list';

export const metadata: Metadata = { title: 'Agreements' };

/** The workspace Agreements section (§3.5): the employee's standard company agreements to read and sign. */
export default function WorkspaceAgreementsPage() {
  return (
    <div className="space-y-6">
      <PageHeader
        title="Agreements"
        description="Read and sign your standard company agreements. Completed agreements stay here to download."
        editorial
      />
      <AgreementsList />
    </div>
  );
}
