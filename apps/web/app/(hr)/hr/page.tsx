import type { Metadata } from 'next';
import { PageHeader } from '@/components/page-header';
import { OnboardEmployeeDialog } from '@/components/hr/onboard-employee-dialog';
import { RoleDashboard } from '@/components/dashboard/role-dashboard';
import { TodayChip } from '@/components/accountant/today-chip';

export const metadata: Metadata = { title: 'Dashboard' };

export default function HrDashboardPage() {
  return (
    <div className="space-y-8">
      <PageHeader
        title="HR workspace"
        description="Onboard new employees and track their progress."
        actions={
          <>
            <TodayChip />
            <OnboardEmployeeDialog />
          </>
        }
        editorial
      />
      {/* Cap the tint to the first 3 stats — HR has up to 5, and 5 tinted cards over-uses the accent. */}
      <RoleDashboard heroStats={3} />
    </div>
  );
}
