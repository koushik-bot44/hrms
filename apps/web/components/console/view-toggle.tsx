'use client';

import * as React from 'react';
import { LayoutList, Rows3 } from 'lucide-react';
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs';

export type ViewMode = 'grouped' | 'all';

/**
 * Grouped / All, as a segmented control.
 *
 * Built on the repo's existing Tabs primitive, which already IS a pill segmented control — a second
 * hand-rolled one would be a divergent implementation of something that exists.
 *
 * Both modes are first-class. Grouped is for auditing a company; All is the ticker you watch when you
 * care about the gate rather than the org chart. The mode is passed into the grouping function, so
 * this component only reports the operator's choice — it decides no layout.
 */
export function ViewToggle({
  value,
  onChange,
  className,
}: {
  value: ViewMode;
  onChange: (mode: ViewMode) => void;
  className?: string;
}) {
  return (
    <Tabs value={value} onValueChange={(v) => onChange(v as ViewMode)} className={className}>
      <TabsList className="h-9">
        <TabsTrigger value="grouped" className="gap-1.5 py-1 text-xs">
          <LayoutList className="size-3.5" aria-hidden />
          Grouped
        </TabsTrigger>
        <TabsTrigger value="all" className="gap-1.5 py-1 text-xs">
          <Rows3 className="size-3.5" aria-hidden />
          All
        </TabsTrigger>
      </TabsList>
    </Tabs>
  );
}

/**
 * Remembers the operator's chosen lens across reloads.
 *
 * Per-viewer convenience only, so every access is guarded: a private window, cleared site data or a
 * browser blocking site storage all THROW here rather than returning empty, and losing the preference
 * costs nothing. Reads lazily on mount so server and client render the same first paint.
 */
export function usePersistedViewMode(storageKey: string, fallback: ViewMode = 'grouped') {
  const [mode, setMode] = React.useState<ViewMode>(fallback);

  React.useEffect(() => {
    try {
      const stored = window.localStorage.getItem(`console.view.${storageKey}`);
      if (stored === 'grouped' || stored === 'all') setMode(stored);
    } catch {
      // Storage unavailable — the toggle still works, it just forgets between visits.
    }
  }, [storageKey]);

  const update = React.useCallback(
    (next: ViewMode) => {
      setMode(next);
      try {
        window.localStorage.setItem(`console.view.${storageKey}`, next);
      } catch {
        // As above.
      }
    },
    [storageKey],
  );

  return [mode, update] as const;
}
