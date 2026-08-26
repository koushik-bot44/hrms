'use client';

import * as React from 'react';
import Link from 'next/link';
import { ChevronRight, LogIn } from 'lucide-react';
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from '@/components/ui/collapsible';
import { Badge } from '@/components/ui/badge';
import { istDayLabel, istTime } from '@/lib/date';
import type { MissingOut } from '@/lib/api/iclock';
import { cn } from '@/lib/utils';

/**
 * "Missing OUT" — people who arrived on the last completed shift day and never tapped out.
 *
 * <p>Surfacing only. No OUT is invented here or anywhere behind it; the person's day view already shows
 * the unpaired session as an honest gap, and fixing it is regularisation, which is a P3 surface. Saying
 * that in the help text matters, because a list of problems with no visible remedy invites someone to
 * invent one.
 *
 * <p>Renders nothing at all when the list is empty — an always-present "Missing OUT (0)" trains the
 * operator to ignore the heading, and then to miss it on the morning it says 11.
 */
export function MissingOutSection({
  rows,
  shiftDate,
  siteId,
}: {
  rows: MissingOut[];
  shiftDate: string;
  siteId: string | null;
}) {
  // Collapsed by default: it is a "yesterday needs attention" list, not the thing being watched now.
  const [open, setOpen] = React.useState(false);

  if (rows.length === 0) return null;

  return (
    <Collapsible
      open={open}
      onOpenChange={setOpen}
      className="overflow-hidden rounded-xl border border-border bg-card"
    >
      <CollapsibleTrigger asChild>
        <button
          type="button"
          className={cn(
            'flex w-full items-center gap-2 px-4 py-2.5 text-left transition-colors',
            'hover:bg-accent focus-visible:outline-none focus-visible:ring-2',
            'focus-visible:ring-ring focus-visible:ring-inset',
          )}
        >
          <ChevronRight
            className={cn('size-4 shrink-0 text-muted-foreground transition-transform', open && 'rotate-90')}
            aria-hidden
          />
          <span className="min-w-0 flex-1 truncate text-sm font-medium">
            Missing OUT — {istDayLabel(shiftDate)}
          </span>
          <Badge variant="warning" className="tabular-nums">
            {rows.length}
          </Badge>
        </button>
      </CollapsibleTrigger>
      <CollapsibleContent>
        <div className="border-t border-border px-4 py-3">
          <p className="mb-3 text-xs text-muted-foreground">
            These people arrived and never tapped out at the gate. Nothing has been assumed or filled in
            — their day view shows the unpaired session as it stands. Correcting a day is
            regularisation, which is not built yet.
          </p>
          <ul className="flex flex-wrap gap-2">
            {rows.map((r) => (
              <li key={r.personId}>
                <Link
                  href={`/super-admin/time-attendance/people/${r.personId}${siteId ? `?site=${siteId}` : ''}`}
                  className={cn(
                    'inline-flex items-center gap-2 rounded-lg border border-border bg-background',
                    'px-2.5 py-1.5 text-xs transition-colors hover:bg-accent',
                  )}
                >
                  <LogIn className="size-3.5 shrink-0 text-muted-foreground" aria-hidden />
                  <span className="font-medium">{r.name ?? 'Unnamed'}</span>
                  {r.companyName ? (
                    <span className="text-muted-foreground">{r.companyName}</span>
                  ) : null}
                  <span className="text-muted-foreground">
                    last seen {istTime(r.lastPunchAt)} · {r.lastDirection}
                    {r.lastArea === 'CAFETERIA' ? ' (cafeteria)' : ''}
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        </div>
      </CollapsibleContent>
    </Collapsible>
  );
}
