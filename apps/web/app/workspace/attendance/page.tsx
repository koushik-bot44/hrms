'use client';

import { PageHeader } from '@/components/page-header';
import { ClockCard } from '@/components/attendance/clock-card';
import { AttendanceHistory } from '@/components/attendance/attendance-history';
import { attendanceKeys, getMyAttendance } from '@/lib/api/attendance';

/** The employee's own attendance (§8a): clock in/out + day-grouped history in Asia/Kolkata. */
export default function AttendancePage() {
  return (
    <div className="space-y-6">
      <PageHeader
        title="Attendance"
        description="Clock in and out. Times are shown in India Standard Time (IST)."
      />
      <ClockCard />
      <AttendanceHistory
        fetchPage={(from, to, page, signal) => getMyAttendance(from, to, page, 50, signal)}
        queryKey={(from, to, page) => attendanceKeys.mine(from, to, page)}
      />
    </div>
  );
}
