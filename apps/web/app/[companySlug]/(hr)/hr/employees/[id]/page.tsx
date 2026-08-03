import type { Metadata } from 'next';
import { PageHeader } from '@/components/page-header';
import { VerificationWorkspace } from '@/components/hr/verification-workspace';

export const metadata: Metadata = { title: 'Verify employee' };

export default function HrEmployeeVerifyPage({ params }: { params: { id: string } }) {
  return (
    <div className="space-y-6">
      <PageHeader
        title="Verify employee"
        description="Review each section and document, then approve or reject the application."
      />
      <VerificationWorkspace employeeId={params.id} />
    </div>
  );
}
