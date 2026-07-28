import type { Metadata } from 'next';
import { PageHeader } from '@/components/page-header';
import { Breadcrumb } from '@/components/ui/breadcrumb';
import { SuperAdminEmployeeRecord } from '@/components/super-admin/super-admin-employee-record';

export const metadata: Metadata = { title: 'Employee record' };

export default function SuperAdminEmployeeRecordPage({
  params,
}: {
  params: { companyId: string; id: string };
}) {
  return (
    <div className="space-y-6">
      <Breadcrumb
        homeHref="/super-admin"
        homeLabel="Companies"
        items={[
          { label: 'Company', href: `/super-admin/companies/${params.companyId}` },
          { label: 'Employee record' },
        ]}
      />
      <PageHeader
        title="Employee record"
        description="View all onboarding forms and PDFs; edit Employee Info (Form 2) while the employee is still invited."
      />
      <SuperAdminEmployeeRecord employeeId={params.id} />
    </div>
  );
}
