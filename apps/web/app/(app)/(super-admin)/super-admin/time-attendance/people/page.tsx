import { Suspense } from 'react';
import type { Metadata } from 'next';
import { PeopleScreen } from '@/components/time-attendance/console-screens';
import { LoadingSkeleton } from '@/components/loading-skeleton';

export const metadata: Metadata = { title: 'Attendance roster' };

export default function TimeAttendancePeoplePage() {
  // Reads `?site=` → needs a Suspense boundary on this static route.
  return (
    <Suspense fallback={<LoadingSkeleton lines={8} />}>
      <PeopleScreen />
    </Suspense>
  );
}
