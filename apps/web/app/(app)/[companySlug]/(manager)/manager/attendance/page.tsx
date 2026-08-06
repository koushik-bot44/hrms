import type { Metadata } from 'next';
import { PageHeader } from '@/components/page-header';
import { ManagerAttendanceTabs } from '@/components/manager/attendance-tabs';

export const metadata: Metadata = { title: 'Attendance' };

/**
 * Manager attendance (§8a): the existing live roster + activity feed, PLUS the shared Work Log analytics
 * for the manager's own team (tiles + donut + monthly series + CSV + month/custom-range) — as tabs.
 */
export default function ManagerAttendancePage() {
  return (
    <div className="space-y-6">
      <PageHeader
        title="Attendance"
        description="Your team's log-ins and working hours. Times are in India Standard Time (IST)."
      />
      <ManagerAttendanceTabs />
    </div>
  );
}
