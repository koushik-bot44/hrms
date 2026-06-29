import { LoadingSkeleton } from '@/components/loading-skeleton';

export default function Loading() {
  return (
    <div className="container py-10">
      <LoadingSkeleton lines={4} className="max-w-2xl" />
    </div>
  );
}
