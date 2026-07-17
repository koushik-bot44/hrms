import type { Metadata } from 'next';
import Link from 'next/link';
import { ArrowLeft } from 'lucide-react';
import { PageHeader } from '@/components/page-header';
import { Button } from '@/components/ui/button';
import { SuperAdminEmployeeRecord } from '@/components/super-admin/super-admin-employee-record';

export const metadata: Metadata = { title: 'Employee record' };

export default function SuperAdminEmployeeRecordPage({
  params,
}: {
  params: { companyId: string; id: string };
}) {
  return (
    <div className="space-y-6">
      <Button asChild variant="ghost" size="sm" className="-ml-2 w-fit text-muted-foreground">
        <Link href={`/super-admin/companies/${params.companyId}`}>
          <ArrowLeft className="size-4" />
          Back to company
        </Link>
      </Button>
      <PageHeader
        title="Employee record"
        description="View all onboarding forms and PDFs; edit Employee Info (Form 2) while the employee is still invited."
      />
      <SuperAdminEmployeeRecord employeeId={params.id} />
    </div>
  );
}
