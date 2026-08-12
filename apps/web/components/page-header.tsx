import * as React from 'react';
import { cn } from '@/lib/utils';

export interface PageHeaderProps {
  title: string;
  description?: string;
  /** Right-aligned actions (buttons, etc.). */
  actions?: React.ReactNode;
  /** Editorial mode — a larger, roomier heading for the spacious landing/hero screens. Off by default. */
  editorial?: boolean;
  className?: string;
}

/** Consistent page heading: title + description on the left, actions on the right. */
export function PageHeader({ title, description, actions, editorial = false, className }: PageHeaderProps) {
  return (
    <div
      className={cn(
        'flex flex-col gap-4 border-b sm:flex-row sm:items-end sm:justify-between',
        editorial ? 'pb-8' : 'pb-6',
        className,
      )}
    >
      <div className="space-y-1.5">
        <h1 className={cn('font-semibold tracking-tight', editorial ? 'text-4xl' : 'text-3xl')}>
          {title}
        </h1>
        {description ? (
          <p
            className={cn(
              'max-w-2xl leading-relaxed text-muted-foreground',
              editorial ? 'text-base' : 'text-sm',
            )}
          >
            {description}
          </p>
        ) : null}
      </div>
      {/* Wrap on a phone so a wide action cluster (e.g. a chip + a primary button) never overflows the row;
          stays a non-shrinking right-aligned group on desktop. */}
      {actions ? <div className="flex flex-wrap items-center gap-2 sm:shrink-0">{actions}</div> : null}
    </div>
  );
}
