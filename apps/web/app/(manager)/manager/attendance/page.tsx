import type { Metadata } from 'next';
import { PageHeader } from '@/components/page-header';
import { TeamAttendance } from '@/components/manager/team-attendance';
import { AttendanceActivityFeed } from '@/components/manager/attendance-activity-feed';

export const metadata: Metadata = { title: 'Attendance' };

/** Manager attendance (§8a): team roster + drill-down, alongside the pull-based activity feed. */
export default function ManagerAttendancePage() {
  return (
    <div className="space-y-6">
      <PageHeader
        title="Attendance"
        description="Your team's clock-ins and working hours. Times are in India Standard Time (IST)."
      />
      <div className="grid gap-6 lg:grid-cols-3">
        <div className="lg:col-span-2">
          <TeamAttendance />
        </div>
        <div className="lg:col-span-1">
          <AttendanceActivityFeed />
        </div>
      </div>
    </div>
  );
}
