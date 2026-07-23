'use client';

import * as React from 'react';
import { Cell, Pie, PieChart, ResponsiveContainer, Tooltip } from 'recharts';
import {
  Building2,
  CheckCircle2,
  Clock,
  Filter,
  Layers,
  PieChart as PieIcon,
  ShieldAlert,
  UserRound,
  Users,
} from 'lucide-react';
import type { OnboardingFunnel, OpsMetrics, PlatformOverview as Overview } from '@/lib/contract';
import { getHierarchyOverview } from '@/lib/api/hierarchy';
import { useApiQuery } from '@/lib/api/hooks';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { PageHeader } from '@/components/page-header';
import { EmptyState } from '@/components/empty-state';
import { LoadingSkeleton } from '@/components/loading-skeleton';
import { cn } from '@/lib/utils';
import { ALL_STATUSES, funnelTotal, MAIN_PATH, OFF_PATH, STATUS_META } from '@/components/hierarchy/status-meta';
import { HierarchyTrends } from '@/components/hierarchy/hierarchy-trends';
import { CompanyBreakdownPanel } from '@/components/hierarchy/company-breakdown';

const REFRESH_MS = 45_000;
const n = (v: number) => v.toLocaleString();

/**
 * The HIERARCHY "Platform Overview" (§2): a single-glance, read-only, cross-platform dashboard — headline
 * totals, the onboarding funnel + status-distribution donut (both from the same counts), monthly trends,
 * employees-per-company + org drill-down, and ops metric cards. Live-on-load + ~45s polling + on-focus
 * refetch. Every figure is bound to the server's aggregates — the UI only formats and computes % (never
 * recomputes a metric). No employee identities appear anywhere.
 */
export function PlatformOverview() {
  const query = useApiQuery(['hierarchy-overview'], (signal) => getHierarchyOverview(signal), {
    refetchOnMount: true,
    refetchOnWindowFocus: true,
    refetchInterval: REFRESH_MS,
    placeholderData: (p) => p,
  });
  const refreshing = query.isFetching && !query.isLoading;

  return (
    <div className="space-y-6">
      <PageHeader
        title="Platform Overview"
        description="Cross-platform, read-only summaries and counts. Individual records, PII, attendance and leave are never shown here."
        actions={<LiveDot refreshing={refreshing} loading={query.isLoading} />}
      />

      {query.isLoading ? (
        <LoadingSkeleton lines={10} />
      ) : query.isError || !query.data ? (
        <EmptyState
          icon={PieIcon}
          title="Couldn't load the overview"
          description={query.error?.message ?? 'Please try again.'}
        />
      ) : (
        <div className={cn('space-y-6 transition-opacity', refreshing && 'opacity-80')}>
          <OverviewBody data={query.data} />
        </div>
      )}

      {/* These sections poll on their own so each stays current. */}
      <HierarchyTrends />
      <CompanyBreakdownPanel />
    </div>
  );
}

function OverviewBody({ data }: { data: Overview }) {
  const { totals, funnel, ops } = data;
  return (
    <>
      {/* 1 — Headline totals. */}
      <div className="grid gap-4 sm:grid-cols-3">
        <Stat
          icon={Building2}
          label="Companies"
          value={n(totals.companies.total)}
          sub={`${n(totals.companies.active)} active · ${n(totals.companies.archived)} archived`}
        />
        <Stat icon={Layers} label="Teams" value={n(totals.teams)} />
        <Stat icon={Users} label="Employees" value={n(totals.employees)} />
      </div>

      <Card>
        <CardHeader className="pb-2">
          <CardTitle className="flex items-center gap-2 text-sm font-medium text-muted-foreground">
            <UserRound className="size-4" />
            Staff assigned, by role
          </CardTitle>
        </CardHeader>
        <CardContent>
          <div className="grid grid-cols-2 gap-4 sm:grid-cols-5">
            <MiniStat label="Company Admins" value={totals.staff.companyAdmins} />
            <MiniStat label="HRs" value={totals.staff.hrs} />
            <MiniStat label="Managers" value={totals.staff.managers} />
            <MiniStat label="Accountants" value={totals.staff.accountants} />
            <MiniStat label="Accounts Admins" value={totals.staff.accountsAdmins} />
          </div>
        </CardContent>
      </Card>

      {/* 2 — Ops health. */}
      <div className="grid gap-4 sm:grid-cols-3">
        <OpsCard
          icon={CheckCircle2}
          label="Onboarding completion"
          value={`${(ops.onboardingCompletionRate * 100).toFixed(1)}%`}
          sub={`${n(ops.approved)} approved of ${n(ops.totalOnboarded)} onboarded`}
        />
        <OpsCard
          icon={Clock}
          label="Avg. time to approval"
          value={ops.averageTimeToApprovalDays == null ? '—' : `${ops.averageTimeToApprovalDays} days`}
          sub="Onboard → Manager approval"
        />
        <StuckCard ops={ops} />
      </div>

      {/* 3 — Onboarding pipeline: funnel + distribution, from the same counts. */}
      <div className="grid gap-4 lg:grid-cols-2">
        <FunnelCard funnel={funnel} />
        <DistributionCard funnel={funnel} />
      </div>
    </>
  );
}

// --- Onboarding funnel -----------------------------------------------------

function FunnelCard({ funnel }: { funnel: OnboardingFunnel }) {
  const max = Math.max(1, ...MAIN_PATH.map((k) => funnel[k]));
  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-base">
          <Filter className="size-4 text-muted-foreground" />
          Onboarding funnel
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="space-y-2.5">
          {MAIN_PATH.map((k) => (
            <div key={k} className="space-y-1">
              <div className="flex items-center justify-between text-sm">
                <span className="text-muted-foreground">{STATUS_META[k].label}</span>
                <span className="font-semibold tabular-nums">{n(funnel[k])}</span>
              </div>
              <div className="h-2.5 overflow-hidden rounded-full bg-muted">
                <div
                  className="h-full rounded-full transition-all"
                  style={{ width: `${(funnel[k] / max) * 100}%`, background: STATUS_META[k].color }}
                />
              </div>
            </div>
          ))}
        </div>
        {/* Off the main path — shown distinctly. */}
        <div className="flex flex-wrap gap-2 border-t pt-3">
          {OFF_PATH.map((k) => (
            <span
              key={k}
              className="inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs"
              title="Off the main onboarding path"
            >
              <span className="size-2 rounded-full" style={{ background: STATUS_META[k].color }} />
              {STATUS_META[k].label}
              <span className="font-semibold tabular-nums">{n(funnel[k])}</span>
            </span>
          ))}
        </div>
      </CardContent>
    </Card>
  );
}

// --- Status distribution donut ---------------------------------------------

function DistributionCard({ funnel }: { funnel: OnboardingFunnel }) {
  const total = funnelTotal(funnel);
  const slices = ALL_STATUSES.map((k) => ({
    key: k,
    label: STATUS_META[k].label,
    color: STATUS_META[k].color,
    value: funnel[k],
  })).filter((s) => s.value > 0);

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-base">
          <PieIcon className="size-4 text-muted-foreground" />
          Status distribution
        </CardTitle>
      </CardHeader>
      <CardContent>
        {total === 0 ? (
          <EmptyState icon={PieIcon} title="No employees yet" description="The distribution appears once employees are onboarded." />
        ) : (
          <div className="grid items-center gap-4 sm:grid-cols-[minmax(0,11rem)_1fr]">
            <div className="h-44">
              <ResponsiveContainer width="100%" height="100%">
                <PieChart>
                  <Pie data={slices} dataKey="value" nameKey="label" innerRadius={48} outerRadius={72} paddingAngle={2} strokeWidth={0}>
                    {slices.map((s) => (
                      <Cell key={s.key} fill={s.color} />
                    ))}
                  </Pie>
                  <Tooltip
                    formatter={(v, label) => [`${n(Number(v))} · ${pct(Number(v), total)}%`, String(label)]}
                    contentStyle={{ borderRadius: 'var(--radius)', border: '1px solid hsl(var(--border))', background: 'hsl(var(--card))', fontSize: 12 }}
                  />
                </PieChart>
              </ResponsiveContainer>
            </div>
            <ul className="space-y-1.5 text-sm">
              {slices.map((s) => (
                <li key={s.key} className="flex items-center justify-between gap-3">
                  <span className="flex items-center gap-2 text-muted-foreground">
                    <span className="size-2.5 rounded-full" style={{ background: s.color }} />
                    {s.label}
                  </span>
                  <span className="tabular-nums">
                    <span className="font-medium">{n(s.value)}</span>
                    <span className="ml-1 text-xs text-muted-foreground">{pct(s.value, total)}%</span>
                  </span>
                </li>
              ))}
            </ul>
          </div>
        )}
      </CardContent>
    </Card>
  );
}

// --- small building blocks -------------------------------------------------

function StuckCard({ ops }: { ops: OpsMetrics }) {
  return (
    <Card>
      <CardContent className="space-y-2 p-4">
        <div className="flex items-center justify-between">
          <p className="flex items-center gap-1.5 text-xs text-muted-foreground">
            <ShieldAlert className="size-4" />
            Stuck onboardings
          </p>
        </div>
        <p className={cn('text-2xl font-semibold tabular-nums', ops.stuckOnboardings > 0 && 'text-warning')}>
          {n(ops.stuckOnboardings)}
        </p>
        <p className="text-xs text-muted-foreground">Pre-approval &gt; {ops.stuckThresholdDays} days</p>
        {ops.stuckByStage.length > 0 ? (
          <ul className="space-y-0.5 pt-1 text-xs text-muted-foreground">
            {ops.stuckByStage.map((s) => (
              <li key={s.status} className="flex items-center justify-between">
                <span>{statusLabel(s.status)}</span>
                <span className="font-medium tabular-nums text-foreground">{n(s.count)}</span>
              </li>
            ))}
          </ul>
        ) : null}
      </CardContent>
    </Card>
  );
}

function Stat({
  icon: Icon,
  label,
  value,
  sub,
}: {
  icon: React.ComponentType<{ className?: string }>;
  label: string;
  value: string;
  sub?: string;
}) {
  return (
    <Card>
      <CardContent className="space-y-1 p-4">
        <p className="flex items-center gap-1.5 text-xs text-muted-foreground">
          <Icon className="size-4" />
          {label}
        </p>
        <p className="text-3xl font-semibold tabular-nums">{value}</p>
        {sub ? <p className="text-xs text-muted-foreground">{sub}</p> : null}
      </CardContent>
    </Card>
  );
}

function OpsCard({
  icon: Icon,
  label,
  value,
  sub,
}: {
  icon: React.ComponentType<{ className?: string }>;
  label: string;
  value: string;
  sub: string;
}) {
  return (
    <Card>
      <CardContent className="space-y-1 p-4">
        <p className="flex items-center gap-1.5 text-xs text-muted-foreground">
          <Icon className="size-4" />
          {label}
        </p>
        <p className="text-2xl font-semibold tabular-nums">{value}</p>
        <p className="text-xs text-muted-foreground">{sub}</p>
      </CardContent>
    </Card>
  );
}

function MiniStat({ label, value }: { label: string; value: number }) {
  return (
    <div className="space-y-0.5">
      <p className="text-2xl font-semibold tabular-nums">{n(value)}</p>
      <p className="text-xs text-muted-foreground">{label}</p>
    </div>
  );
}

function LiveDot({ refreshing, loading }: { refreshing: boolean; loading: boolean }) {
  if (loading) return null;
  return (
    <span className="inline-flex items-center gap-1.5 text-xs text-muted-foreground">
      <span className={cn('size-1.5 rounded-full', refreshing ? 'animate-pulse bg-primary' : 'bg-success')} />
      {refreshing ? 'Refreshing…' : 'Live — updates automatically'}
    </span>
  );
}

function pct(value: number, total: number): string {
  if (total <= 0) return '0';
  return ((value / total) * 100).toFixed(1);
}

/** Map a raw EmployeeStatus (e.g. "IN_PROGRESS") to a funnel label for the stuck-by-stage list. */
function statusLabel(status: string): string {
  const key = status
    .toLowerCase()
    .replace(/_([a-z])/g, (_, c: string) => c.toUpperCase()) as keyof typeof STATUS_META;
  return STATUS_META[key]?.label ?? status;
}
