'use client';

import { cn } from '@/lib/utils';
import { useApiStatus, type ApiStatus } from '@/hooks/use-api-status';

const STATUS_META: Record<ApiStatus, { label: string; dot: string; text: string }> = {
  loading: { label: 'Checking API…', dot: 'bg-slate-400 animate-pulse', text: 'text-slate-500' },
  online: { label: 'API online', dot: 'bg-green-500', text: 'text-green-600' },
  degraded: { label: 'API up · database down', dot: 'bg-amber-500', text: 'text-amber-600' },
  offline: { label: 'API unreachable', dot: 'bg-red-500', text: 'text-red-600' },
};

/** Small live indicator: green online, amber degraded, red offline. */
export function ApiStatusIndicator({ className }: { className?: string }) {
  const { status } = useApiStatus();
  const meta = STATUS_META[status];

  return (
    <span
      className={cn('inline-flex items-center gap-2 text-xs font-medium', meta.text, className)}
      role="status"
      aria-live="polite"
    >
      <span className={cn('size-2 rounded-full', meta.dot)} aria-hidden />
      {meta.label}
    </span>
  );
}
