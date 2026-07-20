'use client';

import { UserRole } from '@/lib/contract';
import { RequireRole } from '@/components/require-role';
import { PageHeader } from '@/components/page-header';
import { TeamRequests } from '@/components/accountant/team-requests';

/**
 * The team Accountant's document-request inbox (§8d). ACCOUNTANT-only — the Accounts Admin (shared area,
 * no team) is bounced by the guard. Requests routed to this accountant only; pick up, upload, resolve.
 */
export default function AccountantRequestsPage() {
  return (
    <RequireRole roles={[UserRole.ACCOUNTANT]}>
      <div className="space-y-6">
        <PageHeader
          title="Requests"
          description="Document requests from your team — pick one up, upload the document(s), and resolve it."
        />
        <TeamRequests />
      </div>
    </RequireRole>
  );
}
