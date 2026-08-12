'use client';

import * as React from 'react';
import {
  Area,
  AreaChart,
  CartesianGrid,
  Legend,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import { TrendingUp } from 'lucide-react';
import { getHierarchyTrends } from '@/lib/api/hierarchy';
import { useApiQuery } from '@/lib/api/hooks';
import { monthLabel } from '@/lib/date';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { EmptyState } from '@/components/empty-state';
import { Skeleton } from '@/components/ui/skeleton';
import { cn } from '@/lib/utils';

const REFRESH_MS = 60_000;
const RANGES = [6, 12, 24] as const;

const JOINED = 'hsl(var(--primary))';
const APPROVED = 'hsl(var(--success))';
const OFFBOARDED = 'hsl(var(--muted-foreground))';

/** Human label for a raw series key (drives both the legend and the tooltip so they never drift). */
function seriesLabel(key: string): string {
  if (key === 'joined') return 'Onboarded';
  if (key === 'approved') return 'Approved';
  return 'Offboarded';
}

/** Monthly onboarding trends (§2): onboarded (area) + approved + offboarded (lines) per IST month, all real. */
export function HierarchyTrends() {
  const [months, setMonths] = React.useState<number>(12);
  const query = useApiQuery(
    ['hierarchy-trends', months],
    (signal) => getHierarchyTrends(months, signal),
    { refetchOnMount: true, refetchOnWindowFocus: true, refetchInterval: REFRESH_MS, placeholderData: (p) => p },
  );

  const data = (query.data?.series ?? []).map((p) => ({
    month: monthLabel(p.month).replace(/ \d{4}$/, ''), // "Jul" (year dropped for a tight axis)
    key: p.month,
    joined: p.joined,
    approved: p.approved,
    offboarded: p.offboarded,
  }));
  // A successful-but-empty (or all-zero) series would draw a blank grid — show a calm empty state instead.
  const hasActivity = data.some((d) => d.joined > 0 || d.approved > 0 || d.offboarded > 0);

  return (
    <Card>
      <CardHeader className="flex-col gap-3 sm:flex-row sm:items-center sm:justify-between sm:space-y-0">
        <div className="space-y-1">
          <CardTitle className="flex items-center gap-2 text-base">
            <TrendingUp className="size-4 text-muted-foreground" />
            Onboarding trends
          </CardTitle>
          <p className="text-xs text-muted-foreground">
            Onboarded, approved and offboarded per month (Asia/Kolkata).
          </p>
        </div>
        <div className="flex items-center gap-1 rounded-md border p-1">
          {RANGES.map((r) => (
            <button
              key={r}
              type="button"
              onClick={() => setMonths(r)}
              className={cn(
                // Matches the period picker: a >=44px tap target on mobile, compact on desktop.
                'inline-flex min-h-11 items-center justify-center rounded px-3.5 text-sm font-medium transition-colors sm:min-h-0 sm:px-3 sm:py-1.5 sm:text-xs',
                months === r ? 'bg-primary text-primary-foreground' : 'text-muted-foreground hover:bg-accent',
              )}
            >
              {r}m
            </button>
          ))}
        </div>
      </CardHeader>
      <CardContent className="space-y-3">
        {query.isLoading ? (
          <Skeleton className="h-64 w-full rounded-md" />
        ) : query.isError ? (
          <EmptyState icon={TrendingUp} title="Couldn't load trends" description={query.error?.message ?? 'Please try again.'} />
        ) : !hasActivity ? (
          <EmptyState
            icon={TrendingUp}
            title="No onboarding activity"
            description="No employees were onboarded or approved in this period."
          />
        ) : (
          <>
            <div className="h-64">
              <ResponsiveContainer width="100%" height="100%">
                <AreaChart data={data} margin={{ top: 4, right: 8, left: -16, bottom: 0 }}>
                  <defs>
                    {/* Soft token-derived gradient fills — indigo for onboarded, green for approved. */}
                    <linearGradient id="areaJoined" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="0%" stopColor={JOINED} stopOpacity={0.35} />
                      <stop offset="100%" stopColor={JOINED} stopOpacity={0} />
                    </linearGradient>
                  </defs>
                  <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="hsl(var(--border))" />
                  <XAxis dataKey="month" tick={{ fontSize: 11, fill: 'hsl(var(--muted-foreground))' }} axisLine={false} tickLine={false} interval="preserveStartEnd" />
                  <YAxis allowDecimals={false} tick={{ fontSize: 11, fill: 'hsl(var(--muted-foreground))' }} axisLine={false} tickLine={false} width={36} />
                  <Tooltip
                    cursor={{ stroke: 'hsl(var(--border))' }}
                    formatter={(v, n) => [String(v), seriesLabel(String(n))]}
                    labelFormatter={(_l, p) => (p?.[0]?.payload ? monthLabel(p[0].payload.key) : '')}
                    contentStyle={{ borderRadius: 'var(--radius)', border: '1px solid hsl(var(--border))', background: 'hsl(var(--card))', fontSize: 12 }}
                  />
                  <Legend
                    verticalAlign="top"
                    height={28}
                    formatter={(value) => seriesLabel(String(value))}
                    wrapperStyle={{ fontSize: 12 }}
                  />
                  {/* Onboarded is the volume baseline (filled); approved + offboarded ride on top as LINES
                      (no second fill) so the three series never occlude each other. */}
                  <Area type="monotone" dataKey="joined" name="joined" stroke={JOINED} strokeWidth={2} fill="url(#areaJoined)" />
                  <Area type="monotone" dataKey="approved" name="approved" stroke={APPROVED} strokeWidth={2} fill="none" fillOpacity={0} />
                  <Area type="monotone" dataKey="offboarded" name="offboarded" stroke={OFFBOARDED} strokeWidth={2} strokeDasharray="4 3" fill="none" fillOpacity={0} />
                </AreaChart>
              </ResponsiveContainer>
            </div>
          </>
        )}
      </CardContent>
    </Card>
  );
}
