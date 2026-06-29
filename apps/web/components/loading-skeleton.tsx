import { Skeleton } from '@/components/ui/skeleton';
import { cn } from '@/lib/utils';

export interface LoadingSkeletonProps {
  lines?: number;
  className?: string;
}

/** Accessible multi-line placeholder used while data loads. */
export function LoadingSkeleton({ lines = 3, className }: LoadingSkeletonProps) {
  return (
    <div
      className={cn('space-y-3', className)}
      role="status"
      aria-busy="true"
      aria-live="polite"
    >
      <span className="sr-only">Loading…</span>
      {Array.from({ length: lines }).map((_, i) => (
        <Skeleton
          key={i}
          className={cn('h-4 w-full', i === lines - 1 && lines > 1 && 'w-2/3')}
        />
      ))}
    </div>
  );
}

/** Card-shaped skeleton, for grids/lists of cards. */
export function CardSkeleton({ className }: { className?: string }) {
  return (
    <div className={cn('rounded-xl border bg-card p-6 shadow-card', className)}>
      <Skeleton className="mb-4 h-10 w-10 rounded-lg" />
      <Skeleton className="mb-2 h-4 w-1/2" />
      <LoadingSkeleton lines={2} />
    </div>
  );
}
