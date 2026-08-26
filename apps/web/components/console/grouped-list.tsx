'use client';

import * as React from 'react';
import { ChevronRight } from 'lucide-react';
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from '@/components/ui/collapsible';
import { Badge } from '@/components/ui/badge';
import { EmptyState } from '@/components/empty-state';
import { groupItems, type Group, type GroupOptions } from '@/lib/console/grouping';
import { cn } from '@/lib/utils';

/**
 * The console's ONE grouped, collapsible listing.
 *
 * The UX standard forbids long flat lists anywhere: every listing groups, shows per-group counts,
 * collapses large groups by default, and orders groups freshest-first. Building that per screen is how
 * four screens end up with four slightly different behaviours, so this is the single implementation and
 * the screens supply only a row renderer.
 *
 * The layout decision itself lives in `lib/console/grouping.ts` as a pure function, which is what the
 * Gate A acceptance test asserts against — there is no DOM harness in this app, and asserting the rule
 * is both possible today and fails for the right reason.
 */

export interface GroupedListProps<T> {
  items: readonly T[];
  grouping: GroupOptions<T>;
  /** One row. Keep it one line tall — per-row actions belong in a row menu, not spread across it. */
  renderItem: (item: T) => React.ReactNode;
  itemKey: (item: T) => string;
  /** Shown when there is nothing at all. */
  empty?: React.ReactNode;
  /** Tone for the count badge, so a column can carry its own semantics. */
  badgeVariant?: 'neutral' | 'success' | 'warning' | 'primarySoft' | 'outline';
  /** Force every group open — used when a caller has already filtered to a handful. */
  expandAll?: boolean;
  className?: string;
  /** Persists expand/collapse per group for this listing; omit for ephemeral state. */
  storageKey?: string;
}

export function GroupedList<T>({
  items,
  grouping,
  renderItem,
  itemKey,
  empty,
  badgeVariant = 'neutral',
  expandAll = false,
  className,
  storageKey,
}: GroupedListProps<T>) {
  const groups = React.useMemo(() => groupItems(items, grouping), [items, grouping]);

  // Which groups the operator has explicitly toggled. Anything not in here uses defaultCollapsed, so a
  // group that grows past the threshold folds itself without overriding a deliberate choice.
  const [overrides, setOverrides] = React.useState<Record<string, boolean>>(() =>
    readStored(storageKey),
  );

  const setOpen = (key: string, open: boolean) => {
    setOverrides((prev) => {
      const next = { ...prev, [key]: open };
      writeStored(storageKey, next);
      return next;
    });
  };

  if (groups.length === 0) {
    return empty ?? null;
  }

  return (
    <div className={cn('space-y-2', className)}>
      {groups.map((g) => {
        // The function decided there is no header — 'all' mode, the flat ticker. Render the rows and
        // nothing else. The renderer never inspects the mode; it obeys the flag it was handed, which is
        // what keeps the layout decision in one testable place.
        if (!g.showHeader) {
          return (
            <ul
              key={g.key}
              className="divide-y divide-border overflow-hidden rounded-xl border border-border bg-card"
            >
              {g.items.map((item) => (
                <li key={itemKey(item)}>{renderItem(item)}</li>
              ))}
            </ul>
          );
        }

        const open = expandAll || (overrides[g.key] ?? !g.defaultCollapsed);
        return (
          <Collapsible
            key={g.key}
            open={open}
            onOpenChange={(next) => setOpen(g.key, next)}
            className="overflow-hidden rounded-xl border border-border bg-card"
          >
            <CollapsibleTrigger asChild>
              <button
                type="button"
                className={cn(
                  'flex w-full items-center gap-2 px-3 py-2.5 text-left transition-colors',
                  'hover:bg-accent focus-visible:outline-none focus-visible:ring-2',
                  'focus-visible:ring-ring focus-visible:ring-inset',
                )}
              >
                <ChevronRight
                  className={cn(
                    'size-4 shrink-0 text-muted-foreground transition-transform',
                    open && 'rotate-90',
                  )}
                  aria-hidden
                />
                <span className="min-w-0 flex-1 truncate text-sm font-medium">{g.label}</span>
                <Badge variant={badgeVariant} className="tabular-nums">
                  {g.count}
                </Badge>
              </button>
            </CollapsibleTrigger>
            <CollapsibleContent>
              <ul className="divide-y divide-border border-t border-border">
                {g.items.map((item) => (
                  <li key={itemKey(item)}>{renderItem(item)}</li>
                ))}
              </ul>
            </CollapsibleContent>
          </Collapsible>
        );
      })}
    </div>
  );
}

/** A group-by selector, so the axis is the operator's choice rather than ours. */
export interface GroupByOption<T> {
  value: string;
  label: string;
  grouping: GroupOptions<T>;
}

export function useGroupBy<T>(options: GroupByOption<T>[], initial?: string) {
  const [value, setValue] = React.useState(initial ?? options[0]?.value ?? '');
  const active = options.find((o) => o.value === value) ?? options[0];
  return { value, setValue, grouping: active?.grouping, options };
}

/** Convenience empty state so screens do not each invent one. */
export function GroupedListEmpty({ title, description }: { title: string; description?: string }) {
  return <EmptyState title={title} description={description} className="py-10" />;
}

// --- expand/collapse persistence ---------------------------------------------------------------
// Per-viewer convenience only: which groups are open is not worth a round-trip, and losing it costs
// nothing. Every access is guarded — a private window, cleared site data, or a browser blocking site
// storage all make these throw rather than return empty.

function readStored(key?: string): Record<string, boolean> {
  if (!key || typeof window === 'undefined') return {};
  try {
    const raw = window.localStorage.getItem(`console.groups.${key}`);
    return raw ? (JSON.parse(raw) as Record<string, boolean>) : {};
  } catch {
    return {};
  }
}

function writeStored(key: string | undefined, value: Record<string, boolean>) {
  if (!key || typeof window === 'undefined') return;
  try {
    window.localStorage.setItem(`console.groups.${key}`, JSON.stringify(value));
  } catch {
    // Storage unavailable — the listing still works, it just forgets between visits.
  }
}

export type { Group };
