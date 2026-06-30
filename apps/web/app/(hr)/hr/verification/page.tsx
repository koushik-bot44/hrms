import type { Metadata } from 'next';
import { PageHeader } from '@/components/page-header';
import { EmployeeLookup } from '@/components/hr/employee-lookup';

export const metadata: Metadata = { title: 'Look up by ID' };

export default function HrLookupPage() {
  return (
    <div className="space-y-6">
      <PageHeader
        title="Look up by Employee ID"
        description="Find an approved employee by their ID and view their full record (read-only)."
      />
      <EmployeeLookup />
    </div>
  );
}
