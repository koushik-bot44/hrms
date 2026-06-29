import * as React from 'react';
import { cn } from '@/lib/utils';

export interface EmptyStateProps {
  /** A lucide icon (or any component taking `className`). */
  icon?: React.ComponentType<{ className?: string }>;
  title: string;
  description?: string;
  action?: React.ReactNode;
  className?: string;
}

/** Calm zero-state: icon + title + description + optional action. */
export function EmptyState({ icon: Icon, title, description, action, className }: EmptyStateProps) {
  return (
    <div
      className={cn(
        'flex animate-fade-in flex-col items-center justify-center rounded-lg border border-dashed bg-card/60 px-6 py-14 text-center',
        className,
      )}
    >
      {Icon ? (
        <div className="mb-4 flex size-12 items-center justify-center rounded-full bg-muted text-muted-foreground">
          <Icon className="size-6" />
        </div>
      ) : null}
      <h3 className="text-base font-medium text-foreground">{title}</h3>
      {description ? (
        <p className="mt-1 max-w-sm text-sm text-muted-foreground">{description}</p>
      ) : null}
      {action ? <div className="mt-5">{action}</div> : null}
    </div>
  );
}
