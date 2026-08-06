import type { Metadata } from 'next';
import { PageHeader } from '@/components/page-header';
import { EmployeeQueue } from '@/components/hr/employee-queue';
import { OnboardEmployeeDialog } from '@/components/hr/onboard-employee-dialog';

export const metadata: Metadata = { title: 'Employees' };

export default function HrEmployeesPage() {
  return (
    <div className="space-y-6">
      <PageHeader
        title="Employees"
        description="Your onboarding queue — open a submitted employee to verify, then approve or reject."
        actions={<OnboardEmployeeDialog />}
      />
      <EmployeeQueue />
    </div>
  );
}
