import { Suspense } from 'react';
import type { Metadata } from 'next';
import { DevicesScreen } from '@/components/time-attendance/console-screens';
import { LoadingSkeleton } from '@/components/loading-skeleton';

export const metadata: Metadata = { title: 'Terminals' };

export default function TimeAttendanceDevicesPage() {
  // Reads `?site=` → needs a Suspense boundary on this static route.
  return (
    <Suspense fallback={<LoadingSkeleton lines={8} />}>
      <DevicesScreen />
    </Suspense>
  );
}
