import type { Metadata } from 'next';
import { Suspense } from 'react';
import { PageHeader } from '@/components/page-header';
import { ApprovalsWorkspace } from '@/components/manager/approvals-workspace';
import { RoleDashboard } from '@/components/dashboard/role-dashboard';
import { TodayChip } from '@/components/accountant/today-chip';

export const metadata: Metadata = { title: 'Dashboard' };

export default function ManagerApprovalsPage() {
  return (
    <div className="space-y-8">
      <PageHeader
        title="Manager workspace"
        description="Your team at a glance — who was onboarded onto your team, and recent activity."
        actions={<TodayChip />}
        editorial
      />
      {/* Stats up top, then the read-only team-onboarding history, then the activity feed — both dashboard
          sections share the one deduped ['dashboard'] query. */}
      <RoleDashboard show="stats" />
      {/* ApprovalsWorkspace reads the query string (status) → needs a Suspense boundary. */}
      <Suspense>
        <ApprovalsWorkspace />
      </Suspense>
      <RoleDashboard show="activity" />
    </div>
  );
}
