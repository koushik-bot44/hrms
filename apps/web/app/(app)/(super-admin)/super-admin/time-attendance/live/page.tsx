import { Suspense } from 'react';
import type { Metadata } from 'next';
import { LiveScreen } from '@/components/time-attendance/console-screens';
import { LoadingSkeleton } from '@/components/loading-skeleton';

export const metadata: Metadata = { title: 'Live board' };

export default function TimeAttendanceLivePage() {
  // Reads `?site=` → needs a Suspense boundary on this static route.
  return (
    <Suspense fallback={<LoadingSkeleton lines={8} />}>
      <LiveScreen />
    </Suspense>
  );
}
