import { Suspense } from 'react';
import type { Metadata } from 'next';
import { OverviewScreen } from '@/components/time-attendance/console-screens';
import { LoadingSkeleton } from '@/components/loading-skeleton';

export const metadata: Metadata = { title: 'Time & Attendance' };

export default function TimeAttendancePage() {
  // The console reads its site scope from `?site=` → needs a Suspense boundary on this static route.
  return (
    <Suspense fallback={<LoadingSkeleton lines={8} />}>
      <OverviewScreen />
    </Suspense>
  );
}
