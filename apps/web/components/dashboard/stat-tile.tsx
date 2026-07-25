import * as React from 'react';
import Link from 'next/link';
import { ChevronRight } from 'lucide-react';
import { cn } from '@/lib/utils';
import { Card, CardContent } from '@/components/ui/card';

/**
 * The icon-tile stat card (the richer visual language): a colored, rounded ICON TILE + a muted label + a
 * big value + an optional sub-line, and an optional drill CHEVRON that appears ONLY when a real `href` is
 * given (never a chevron that goes nowhere). Tones are SEMANTIC — pick per the metric's meaning. Backward-
 * compatible and additive: existing stat cards (RoleDashboard's StatCardView) are untouched.
 */
export type StatTone = 'primary' | 'success' | 'warning' | 'danger' | 'neutral';

const TILE_TONES: Record<StatTone, string> = {
  primary: 'bg-primary/10 text-primary',
  success: 'bg-success/10 text-success',
  warning: 'bg-warning/10 text-warning',
  danger: 'bg-destructive/10 text-destructive',
  neutral: 'bg-muted text-muted-foreground',
};

export interface StatTileProps {
  icon: React.ComponentType<{ className?: string }>;
  label: string;
  value: React.ReactNode;
  sub?: React.ReactNode;
  tone?: StatTone;
  /** When set, the whole tile is a link and shows a drill chevron. Omit for a static tile (no chevron). */
  href?: string;
  /** Native tooltip (e.g. a definition) — passed through, behaviour unchanged. */
  title?: string;
  /** `default` = text-3xl (landings); `sm` = text-2xl (dense analytics with long values like durations). */
  size?: 'default' | 'sm';
  className?: string;
}

export function StatTile({
  icon: Icon,
  label,
  value,
  sub,
  tone = 'primary',
  href,
  title,
  size = 'default',
  className,
}: StatTileProps) {
  const body = (
    <Card variant={href ? 'interactive' : 'default'} className={cn('h-full', className)} title={title}>
      <CardContent className="flex items-start gap-4 p-5">
        <div className={cn('flex size-11 shrink-0 items-center justify-center rounded-xl', TILE_TONES[tone])}>
          <Icon className="size-5" />
        </div>
        <div className="min-w-0 flex-1 space-y-0.5">
          <p className="text-xs font-medium text-muted-foreground">{label}</p>
          <p className={cn('font-semibold tracking-tight tabular-nums', size === 'sm' ? 'text-2xl' : 'text-3xl')}>
            {value}
          </p>
          {sub ? <p className="text-xs text-muted-foreground">{sub}</p> : null}
        </div>
        {href ? <ChevronRight className="size-4 shrink-0 self-center text-muted-foreground" aria-hidden /> : null}
      </CardContent>
    </Card>
  );

  return href ? (
    <Link
      href={href}
      className="block rounded-2xl focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background"
    >
      {body}
    </Link>
  ) : (
    body
  );
}

const BAR_TONES: Record<StatTone, string> = {
  primary: 'bg-primary',
  success: 'bg-success',
  warning: 'bg-warning',
  danger: 'bg-destructive',
  neutral: 'bg-muted-foreground',
};

/**
 * A labeled progress-bar row — for SAME-UNIT parts of a whole (e.g. worked vs break of clocked time). The
 * fill colour is a token; the width is a computed percentage. `display` overrides the right-aligned value
 * text (e.g. a duration + %).
 */
export function MeterRow({
  label,
  value,
  max,
  tone = 'primary',
  display,
}: {
  label: string;
  value: number;
  max: number;
  tone?: StatTone;
  display?: React.ReactNode;
}) {
  const pct = max > 0 ? Math.min(100, Math.round((value / max) * 100)) : 0;
  return (
    <div className="space-y-1.5">
      <div className="flex items-center justify-between text-sm">
        <span className="text-muted-foreground">{label}</span>
        <span className="font-medium tabular-nums">{display ?? `${pct}%`}</span>
      </div>
      <div className="h-2 overflow-hidden rounded-full bg-muted">
        <div className={cn('h-full rounded-full transition-all', BAR_TONES[tone])} style={{ width: `${pct}%` }} />
      </div>
    </div>
  );
}
