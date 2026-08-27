import { Suspense } from 'react';
import type { Metadata } from 'next';
import { ReportsScreen } from '@/components/time-attendance/console-screens';
import { LoadingSkeleton } from '@/components/loading-skeleton';

export const metadata: Metadata = { title: 'Reports' };

export default function TimeAttendanceReportsPage() {
  // Reads `?site=` → needs a Suspense boundary on this static route.
  return (
    <Suspense fallback={<LoadingSkeleton lines={10} />}>
      <ReportsScreen />
    </Suspense>
  );
}
