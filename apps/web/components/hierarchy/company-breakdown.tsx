'use client';

import * as React from 'react';
import { Bar, BarChart, Cell, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { Building2, ChevronRight, ShieldCheck } from 'lucide-react';
import type { CompanySizeRow, StaffRef } from '@/lib/contract';
import { getHierarchyBreakdown, getHierarchyCompanies } from '@/lib/api/hierarchy';
import { useApiQuery } from '@/lib/api/hooks';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { EmptyState } from '@/components/empty-state';
import { LoadingSkeleton } from '@/components/loading-skeleton';
import { cn } from '@/lib/utils';
import { ALL_STATUSES, STATUS_META } from '@/components/hierarchy/status-meta';

const REFRESH_MS = 60_000;
const CHART_TOP = 12;

const ACTIVE = 'hsl(var(--primary))';
const ARCHIVED = 'hsl(var(--muted-foreground))';

/** Employees-per-company (size distribution) + a per-company org breakdown drill-down (§2). */
export function CompanyBreakdownPanel() {
  const [selected, setSelected] = React.useState<string | null>(null);
  const companies = useApiQuery(['hierarchy-companies'], (signal) => getHierarchyCompanies(signal), {
    refetchOnMount: true,
    refetchOnWindowFocus: true,
    refetchInterval: REFRESH_MS,
    placeholderData: (p) => p,
  });

  const rows = companies.data?.companies ?? [];
  const chartData = [...rows]
    .sort((a, b) => b.employeeCount - a.employeeCount)
    .slice(0, CHART_TOP)
    .map((c) => ({ name: c.name, key: c.id, value: c.employeeCount, archived: c.archived }));

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-base">
          <Building2 className="size-4 text-muted-foreground" />
          Companies
        </CardTitle>
        <CardDescription>
          Employees per company (top {CHART_TOP} by size); open one for its org structure — teams and
          assigned staff. No employee details are shown.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-5">
        {companies.isLoading ? (
          <LoadingSkeleton lines={6} />
        ) : companies.isError ? (
          <EmptyState icon={Building2} title="Couldn't load companies" description={companies.error?.message ?? 'Please try again.'} />
        ) : rows.length === 0 ? (
          <EmptyState icon={Building2} title="No companies yet" description="Companies will appear here as they're created." />
        ) : (
          <>
            <div style={{ height: Math.max(120, chartData.length * 30 + 20) }}>
              <ResponsiveContainer width="100%" height="100%">
                <BarChart layout="vertical" data={chartData} margin={{ top: 0, right: 16, left: 8, bottom: 0 }}>
                  <XAxis type="number" allowDecimals={false} tick={{ fontSize: 11, fill: 'hsl(var(--muted-foreground))' }} axisLine={false} tickLine={false} />
                  <YAxis type="category" dataKey="name" width={130} tick={{ fontSize: 11, fill: 'hsl(var(--muted-foreground))' }} axisLine={false} tickLine={false} />
                  <Tooltip
                    cursor={{ fill: 'hsl(var(--accent))', opacity: 0.4 }}
                    formatter={(v) => [String(v), 'Employees']}
                    labelFormatter={(l, p) => (p?.[0]?.payload?.archived ? `${l} (archived)` : String(l))}
                    contentStyle={{ borderRadius: 8, border: '1px solid hsl(var(--border))', background: 'hsl(var(--card))', fontSize: 12 }}
                  />
                  <Bar dataKey="value" radius={[0, 3, 3, 0]}>
                    {chartData.map((d) => (
                      <Cell key={d.key} fill={d.archived ? ARCHIVED : ACTIVE} />
                    ))}
                  </Bar>
                </BarChart>
              </ResponsiveContainer>
            </div>

            <div className="grid gap-4 lg:grid-cols-[minmax(0,20rem)_1fr]">
              {/* The full company list — the drill selector. */}
              <div className="max-h-80 space-y-1 overflow-y-auto rounded-md border p-1">
                {rows.map((c) => (
                  <CompanyRow key={c.id} company={c} selected={selected === c.id} onSelect={() => setSelected(c.id)} />
                ))}
              </div>
              <div className="min-w-0">
                {selected ? (
                  <CompanyDetail companyId={selected} />
                ) : (
                  <div className="flex h-full min-h-40 items-center justify-center rounded-md border border-dashed text-sm text-muted-foreground">
                    Select a company to see its teams and assigned staff.
                  </div>
                )}
              </div>
            </div>
          </>
        )}
      </CardContent>
    </Card>
  );
}

function CompanyRow({
  company,
  selected,
  onSelect,
}: {
  company: CompanySizeRow;
  selected: boolean;
  onSelect: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onSelect}
      className={cn(
        'flex w-full items-center gap-2 rounded-md px-2.5 py-2 text-left text-sm transition-colors',
        selected ? 'bg-primary/10 text-foreground' : 'hover:bg-accent/60',
      )}
    >
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2">
          <span className="truncate font-medium">{company.name}</span>
          {company.archived ? (
            <Badge variant="neutral" className="shrink-0 text-[10px]">
              Archived
            </Badge>
          ) : null}
        </div>
        <div className="text-xs text-muted-foreground">
          {company.teamCount} team{company.teamCount === 1 ? '' : 's'} · {company.employeeCount} employee
          {company.employeeCount === 1 ? '' : 's'}
        </div>
      </div>
      <ChevronRight className={cn('size-4 shrink-0 text-muted-foreground', selected && 'text-primary')} />
    </button>
  );
}

function CompanyDetail({ companyId }: { companyId: string }) {
  const query = useApiQuery(
    ['hierarchy-breakdown', companyId],
    (signal) => getHierarchyBreakdown(companyId, signal),
    { refetchOnWindowFocus: true, refetchInterval: REFRESH_MS, placeholderData: (p) => p },
  );

  if (query.isLoading) return <LoadingSkeleton lines={5} />;
  if (query.isError || !query.data) {
    return (
      <EmptyState
        icon={Building2}
        title="Couldn't load the breakdown"
        description={query.error?.message ?? 'Please try again.'}
      />
    );
  }
  const b = query.data;

  return (
    <div className="space-y-4 rounded-md border p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <h3 className="text-sm font-semibold">{b.name}</h3>
          {b.archived ? <Badge variant="neutral">Archived</Badge> : null}
        </div>
        <div className="text-xs text-muted-foreground">
          {b.teamCount} team{b.teamCount === 1 ? '' : 's'} · {b.employeeCount} employee
          {b.employeeCount === 1 ? '' : 's'}
        </div>
      </div>

      {/* By-status chips — same colours/labels as the platform funnel. */}
      <div className="flex flex-wrap gap-x-4 gap-y-1.5">
        {ALL_STATUSES.map((k) => (
          <span key={k} className="inline-flex items-center gap-1.5 text-xs text-muted-foreground">
            <span className="size-2.5 rounded-full" style={{ background: STATUS_META[k].color }} />
            {STATUS_META[k].label}
            <span className="font-medium tabular-nums text-foreground">{b.byStatus[k]}</span>
          </span>
        ))}
      </div>

      <div className="flex items-center gap-2 rounded-md bg-muted/40 px-3 py-2 text-sm">
        <ShieldCheck className="size-4 shrink-0 text-muted-foreground" />
        <span className="text-muted-foreground">Company Admin:</span>
        <StaffName staff={b.companyAdmin} />
      </div>

      <div className="overflow-x-auto rounded-md border">
        <table className="w-full text-sm">
          <thead className="bg-muted/40 text-left text-xs text-muted-foreground">
            <tr>
              <th className="px-3 py-2 font-medium">Team</th>
              <th className="px-3 py-2 font-medium">HR</th>
              <th className="px-3 py-2 font-medium">Manager</th>
              <th className="px-3 py-2 font-medium">Accountant</th>
              <th className="px-3 py-2 text-right font-medium">Employees</th>
            </tr>
          </thead>
          <tbody>
            {b.teams.length === 0 ? (
              <tr>
                <td colSpan={5} className="px-3 py-3 text-center text-muted-foreground">
                  No teams in this company.
                </td>
              </tr>
            ) : (
              b.teams.map((t) => (
                <tr key={t.teamId} className="border-t align-top">
                  <td className="px-3 py-2 font-medium">{t.name}</td>
                  <td className="px-3 py-2"><StaffName staff={t.hr} /></td>
                  <td className="px-3 py-2"><StaffName staff={t.manager} /></td>
                  <td className="px-3 py-2"><StaffName staff={t.accountant} /></td>
                  <td className="px-3 py-2 text-right tabular-nums">{t.employeeCount}</td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}

/** A staff member (name + email), or a muted "—" when the slot is unassigned. */
function StaffName({ staff }: { staff: StaffRef | null }) {
  if (!staff) return <span className="text-muted-foreground">—</span>;
  return (
    <span className="min-w-0">
      <span className="font-medium">{staff.name}</span>
      {staff.email ? <span className="block truncate text-xs text-muted-foreground">{staff.email}</span> : null}
    </span>
  );
}
