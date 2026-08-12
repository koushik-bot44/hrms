'use client';

import * as React from 'react';
import dynamic from 'next/dynamic';
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

// recharts (~heavy) is deferred off the /hierarchy first load — it only mounts once there's activity to draw,
// with the SAME h-64 skeleton the loading state already reserves, so there's no layout shift or visual change.
const TrendsChart = dynamic(() => import('./trends-chart'), {
  ssr: false,
  loading: () => <Skeleton className="h-64 w-full rounded-md" />,
});

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
          <TrendsChart data={data} />
        )}
      </CardContent>
    </Card>
  );
}
