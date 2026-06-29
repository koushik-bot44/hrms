import * as React from 'react';
import { cn } from '@/lib/utils';

export interface PageHeaderProps {
  title: string;
  description?: string;
  /** Right-aligned actions (buttons, etc.). */
  actions?: React.ReactNode;
  className?: string;
}

/** Consistent page heading: title + description on the left, actions on the right. */
export function PageHeader({ title, description, actions, className }: PageHeaderProps) {
  return (
    <div
      className={cn(
        'flex flex-col gap-3 border-b pb-5 sm:flex-row sm:items-center sm:justify-between',
        className,
      )}
    >
      <div className="space-y-1">
        <h1 className="text-xl font-medium tracking-tight">{title}</h1>
        {description ? <p className="text-sm text-muted-foreground">{description}</p> : null}
      </div>
      {actions ? <div className="flex shrink-0 items-center gap-2">{actions}</div> : null}
    </div>
  );
}
