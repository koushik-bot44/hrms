import type { Metadata } from 'next';
import { PageHeader } from '@/components/page-header';
import { EmployeeLookup } from '@/components/hr/employee-lookup';

export const metadata: Metadata = { title: 'Look up employee' };

export default function HrLookupPage() {
  return (
    <div className="space-y-6">
      <PageHeader
        title="Look up an approved employee"
        description="Search by employee ID or name and view their full record (read-only)."
      />
      <EmployeeLookup />
    </div>
  );
}
