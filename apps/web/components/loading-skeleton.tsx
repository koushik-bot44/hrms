import { Skeleton } from '@/components/ui/skeleton';
import { cn } from '@/lib/utils';

export interface LoadingSkeletonProps {
  lines?: number;
  className?: string;
}

/** Accessible multi-line placeholder used while data loads. */
export function LoadingSkeleton({ lines = 3, className }: LoadingSkeletonProps) {
  return (
    <div className={cn('space-y-3', className)} role="status" aria-busy="true" aria-live="polite">
      <span className="sr-only">Loading…</span>
      {Array.from({ length: lines }).map((_, i) => (
        <Skeleton key={i} className={cn('h-4 w-full', i === lines - 1 && lines > 1 && 'w-2/3')} />
      ))}
    </div>
  );
}

/** Table-shaped skeleton for list views. */
export function TableSkeleton({ rows = 5, cols = 4 }: { rows?: number; cols?: number }) {
  return (
    <div className="space-y-3" role="status" aria-busy="true">
      <span className="sr-only">Loading…</span>
      <Skeleton className="h-9 w-64" />
      <div className="rounded-lg border">
        {Array.from({ length: rows }).map((_, r) => (
          <div key={r} className="flex items-center gap-4 border-b p-4 last:border-0">
            {Array.from({ length: cols }).map((_, c) => (
              <Skeleton key={c} className={cn('h-4 flex-1', c === 0 && 'max-w-[10rem]')} />
            ))}
          </div>
        ))}
      </div>
    </div>
  );
}
