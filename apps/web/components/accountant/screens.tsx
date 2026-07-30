import { PageHeader } from '@/components/page-header';
import { RoleDashboard } from '@/components/dashboard/role-dashboard';
import { ViewerEmployeesBrowser } from '@/components/accountant/viewer-employees-browser';
import { TodayChip } from '@/components/accountant/today-chip';
import { ApprovalAudit } from '@/components/accountant/approval-audit';
import { AccountantRecord } from '@/components/accountant/accountant-record';

/**
 * The read-only viewer SCREENS (§2/§6) — one implementation, mounted by BOTH the team-scoped ACCOUNTANT
 * (`/{companySlug}/accountant`) and the platform ACCOUNTS_ADMIN (`/accounts`) route trees. The API scopes
 * every read by the signed-in role, so the UI is identical; each mount only differs in its guard + nav.
 * No screen code is duplicated across the two mounts.
 */

export function AccountantOverviewScreen() {
  return (
    <div className="space-y-8">
      <PageHeader
        editorial
        title="Accounts workspace"
        description="Read-only oversight of approved employees and their records, scoped to your access."
        actions={<TodayChip />}
      />
      <RoleDashboard show="stats" />
      <ViewerEmployeesBrowser />
    </div>
  );
}

export function AccountantAuditScreen() {
  return (
    <div className="space-y-6">
      <PageHeader
        title="Approval audit"
        description="Approval decisions across every company — the only audit trail the Accountant can read."
      />
      <ApprovalAudit />
    </div>
  );
}

export function AccountantRecordScreen({ employeeId }: { employeeId: string }) {
  return (
    <div className="space-y-6">
      <AccountantRecord employeeId={employeeId} />
    </div>
  );
}
