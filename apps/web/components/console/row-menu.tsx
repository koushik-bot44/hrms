'use client';

import * as React from 'react';
import { MoreVertical } from 'lucide-react';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { cn } from '@/lib/utils';

/**
 * The console's per-row action menu.
 *
 * The standard: per-row actions are consolidated into a single menu so rows stay one line tall. Three
 * buttons spread across a row is what makes a 212-row listing unscannable, and it is also what forces
 * the row to grow a second line on a narrow screen.
 *
 * Purely presentational — it renders the actions it is handed, in the order it is handed them. No
 * ordering or grouping decisions live here; those belong to `lib/console/grouping.ts`.
 */

export interface RowAction {
  label: string;
  onSelect: () => void;
  icon?: React.ComponentType<{ className?: string }>;
  /** Renders in the destructive tone and below a separator. */
  danger?: boolean;
  disabled?: boolean;
}

export function RowMenu({ actions, label }: { actions: RowAction[]; label: string }) {
  const usable = actions.filter(Boolean);
  if (usable.length === 0) return null;

  const safe = usable.filter((a) => !a.danger);
  const dangerous = usable.filter((a) => a.danger);

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button
          type="button"
          aria-label={label}
          onClick={(e) => e.stopPropagation()}
          className={cn(
            'inline-flex size-8 shrink-0 items-center justify-center rounded-md text-muted-foreground',
            'transition-colors hover:bg-accent hover:text-accent-foreground',
            'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
          )}
        >
          <MoreVertical className="size-4" aria-hidden />
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" onClick={(e) => e.stopPropagation()}>
        {safe.map((a) => (
          <DropdownMenuItem key={a.label} onSelect={a.onSelect} disabled={a.disabled}>
            {a.icon ? <a.icon className="size-4" aria-hidden /> : null}
            {a.label}
          </DropdownMenuItem>
        ))}
        {dangerous.length > 0 && safe.length > 0 ? <DropdownMenuSeparator /> : null}
        {dangerous.map((a) => (
          <DropdownMenuItem
            key={a.label}
            onSelect={a.onSelect}
            disabled={a.disabled}
            className="text-destructive focus:text-destructive"
          >
            {a.icon ? <a.icon className="size-4" aria-hidden /> : null}
            {a.label}
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
