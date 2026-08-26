import { Suspense } from 'react';
import type { Metadata } from 'next';
import { FeedScreen } from '@/components/time-attendance/console-screens';
import { LoadingSkeleton } from '@/components/loading-skeleton';

export const metadata: Metadata = { title: 'Punch feed' };

export default function TimeAttendanceFeedPage() {
  // Reads `?site=` → needs a Suspense boundary on this static route.
  return (
    <Suspense fallback={<LoadingSkeleton lines={8} />}>
      <FeedScreen />
    </Suspense>
  );
}
