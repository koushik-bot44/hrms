import type { Metadata } from 'next';
import { PageHeader } from '@/components/page-header';
import { VerificationWorkspace } from '@/components/hr/verification-workspace';

export const metadata: Metadata = { title: 'Employee record' };

export default function HrEmployeeVerifyPage({ params }: { params: { id: string } }) {
  return (
    <div className="space-y-6">
      <PageHeader
        title="Employee record"
        description="Verify a new hire's forms and documents, or enter an existing employee's record — then approve."
      />
      <VerificationWorkspace employeeId={params.id} />
    </div>
  );
}
