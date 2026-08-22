'use client';

import Link from 'next/link';
import { Building2, ChevronRight, Download, Layers, Network, Users } from 'lucide-react';
import type { CompanySizeRow, StaffCoverage } from '@/lib/contract';
import { getHierarchyCompanies } from '@/lib/api/hierarchy';
import { useApiQuery } from '@/lib/api/hooks';
import { downloadCsv, toCsv } from '@/lib/csv';
import { istTodayIso } from '@/lib/date';
import { PageHeader } from '@/components/page-header';
import { Breadcrumb } from '@/components/ui/breadcrumb';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { EmptyState } from '@/components/empty-state';
import { LoadingSkeleton } from '@/components/loading-skeleton';
import { cn } from '@/lib/utils';

const n = (v: number) => v.toLocaleString();

/**
 * Organisation — Level 1 (§2 org browser): every company as a carded row (mobile-stacked), with its team +
 * employee counts and STAFF COVERAGE across its teams (HR / Managers / Accountants filled vs total — a gap is
 * the actionable signal). Archived companies are visually distinguished. Each row drills to the company
 * (Level 2). Opaque ids in the URL (platform area). Aggregates only — no employee identity here.
 */
export function OrganisationCompanies() {
  const query = useApiQuery(['hierarchy-companies'], (signal) => getHierarchyCompanies(signal), {
    refetchOnMount: true,
    refetchOnWindowFocus: true,
  });
  const rows = query.data?.companies ?? [];

  return (
    <div className="space-y-5">
      <Breadcrumb items={[{ label: 'Companies' }]} homeHref="/hierarchy" homeLabel="Platform Overview" />
      <PageHeader
        title="Organisation"
        description="Every company on the platform — team and employee counts, and which staff slots are filled. Open one to see its teams and people."
        actions={
          rows.length > 0 ? (
            <Button type="button" variant="outline" size="sm" onClick={() => exportCompaniesCsv(rows)}>
              <Download className="size-4" />
              Export CSV
            </Button>
          ) : undefined
        }
      />

      {query.isLoading ? (
        <LoadingSkeleton lines={8} />
      ) : query.isError ? (
        <EmptyState icon={Network} title="Couldn't load companies" description={query.error?.message ?? 'Please try again.'} />
      ) : rows.length === 0 ? (
        <EmptyState icon={Building2} title="No companies yet" description="Companies appear here as they're created." />
      ) : (
        <div className="space-y-2">
          {rows.map((c) => (
            <Link
              key={c.id}
              href={`/hierarchy/organisation/${encodeURIComponent(c.id)}`}
              className="block rounded-2xl focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background"
            >
              <Card variant="interactive">
                <CardContent className="flex flex-wrap items-center gap-x-4 gap-y-3 p-4">
                  <div className="flex min-w-0 flex-1 items-center gap-3">
                    <span className="flex size-10 shrink-0 items-center justify-center rounded-xl bg-primary/10 text-primary">
                      <Building2 className="size-5" />
                    </span>
                    <div className="min-w-0">
                      <div className="flex items-center gap-2">
                        <span className="truncate font-medium">{c.name}</span>
                        {c.archived ? (
                          <Badge variant="neutral" className="shrink-0 text-[10px]">
                            Archived
                          </Badge>
                        ) : null}
                      </div>
                      <p className="text-xs text-muted-foreground">
                        {n(c.teamCount)} team{c.teamCount === 1 ? '' : 's'} · {n(c.employeeCount)} employee
                        {c.employeeCount === 1 ? '' : 's'}
                      </p>
                    </div>
                  </div>
                  <CoverageChips coverage={c.coverage} />
                  <ChevronRight className="size-4 shrink-0 text-muted-foreground" aria-hidden />
                </CardContent>
              </Card>
            </Link>
          ))}
        </div>
      )}
    </div>
  );
}

/** HR / Managers / Accountants filled vs total teams — an unfilled slot is toned as a warning (the gap). */
function CoverageChips({ coverage }: { coverage: StaffCoverage }) {
  const items: Array<[React.ComponentType<{ className?: string }>, string, number]> = [
    [Users, 'HR', coverage.hrFilled],
    [Layers, 'Mgr', coverage.managersFilled],
    [Building2, 'Acc', coverage.accountantsFilled],
  ];
  return (
    <div className="flex flex-wrap items-center gap-1.5">
      {items.map(([Icon, label, filled]) => {
        const gap = coverage.teams > 0 && filled < coverage.teams;
        return (
          <span
            key={label}
            className={cn(
              'inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-xs tabular-nums',
              coverage.teams === 0
                ? 'text-muted-foreground'
                : gap
                  ? 'border-warning/40 bg-warning/10 text-warning'
                  : 'border-success/30 bg-success/10 text-success',
            )}
            title={`${label}: ${filled} of ${coverage.teams} team${coverage.teams === 1 ? '' : 's'} filled`}
          >
            <Icon className="size-3" aria-hidden />
            {label} {coverage.teams === 0 ? '—' : `${filled}/${coverage.teams}`}
          </span>
        );
      })}
    </div>
  );
}

/** The companies roll-up, exactly as loaded (name, code, status, teams, employees, staff coverage). No PII. */
function exportCompaniesCsv(rows: CompanySizeRow[]): void {
  const headers = ['Company', 'Code', 'Status', 'Teams', 'Employees', 'HR filled', 'Managers filled', 'Accountants filled'];
  const data = rows.map((c) => [
    c.name,
    c.code,
    c.archived ? 'Archived' : 'Active',
    c.teamCount,
    c.employeeCount,
    c.coverage.hrFilled,
    c.coverage.managersFilled,
    c.coverage.accountantsFilled,
  ]);
  downloadCsv(`organisation_companies_${istTodayIso()}.csv`, toCsv(headers, data));
}
