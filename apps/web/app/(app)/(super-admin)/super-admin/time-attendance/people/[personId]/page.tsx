import { Suspense } from 'react';
import type { Metadata } from 'next';
import { PersonDayScreen } from '@/components/time-attendance/console-screens';
import { LoadingSkeleton } from '@/components/loading-skeleton';

export const metadata: Metadata = { title: 'Attendance day' };

export default function TimeAttendancePersonDayPage({
  params,
}: {
  params: { personId: string };
}) {
  // Reads `?site=` to keep the back-link scoped → needs a Suspense boundary.
  return (
    <Suspense fallback={<LoadingSkeleton lines={8} />}>
      <PersonDayScreen personId={params.personId} />
    </Suspense>
  );
}
