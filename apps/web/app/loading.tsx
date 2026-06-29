import { LoadingSkeleton } from '@/components/loading-skeleton';

export default function Loading() {
  return (
    <div className="container py-16">
      <LoadingSkeleton lines={5} className="max-w-2xl" />
    </div>
  );
}
