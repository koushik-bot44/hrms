import type { Metadata } from 'next';
import { PageHeader } from '@/components/page-header';
import { EmployeeLookup } from '@/components/hr/employee-lookup';

export const metadata: Metadata = { title: 'Employees' };

/**
 * Company Admin employees (§6): search any approved employee in the company and open their record to
 * assign or reset a mailbox — the same read-only record view HR uses, with the mailbox controls now
 * shown for a COMPANY_ADMIN too. The list + record reads are company-scoped server-side.
 */
export default function CompanyAdminEmployeesPage() {
  return (
    <div className="space-y-6">
      <PageHeader
        title="Employees"
        description="Find an approved employee to assign or reset their mailbox credentials."
      />
      <EmployeeLookup />
    </div>
  );
}
