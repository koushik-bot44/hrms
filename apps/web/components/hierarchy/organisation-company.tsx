'use client';

import Link from 'next/link';
import { Building2, ChevronRight, Download, ShieldCheck, Users } from 'lucide-react';
import type { CompanyBreakdown, StaffRef, TeamBreakdown } from '@/lib/contract';
import { getHierarchyBreakdown } from '@/lib/api/hierarchy';
import { useApiQuery } from '@/lib/api/hooks';
import { downloadCsv, toCsv } from '@/lib/csv';
import { istTodayIso } from '@/lib/date';
import { PageHeader } from '@/components/page-header';
import { Breadcrumb } from '@/components/ui/breadcrumb';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { StatTile } from '@/components/dashboard/stat-tile';
import { EmptyState } from '@/components/empty-state';
import { LoadingSkeleton } from '@/components/loading-skeleton';
import { surface } from '@/components/ui/surface';
import { cn } from '@/lib/utils';

const n = (v: number) => v.toLocaleString();

/**
 * Organisation — Level 2 (§2 org browser): one company's teams. A company summary (headcount, teams, Company
 * Admin) above; then each team as a carded row (mobile-stacked) with its headcount and assigned HR / Manager /
 * Accountant by NAME (or a clear "Unassigned"). Each row drills to the team's people (Level 3). Staff names are
 * org data (§2); no employee identity appears at this level.
 */
export function OrganisationCompany({ companyId }: { companyId: string }) {
  const query = useApiQuery(['hierarchy-breakdown', companyId], (s) => getHierarchyBreakdown(companyId, s), {
    refetchOnMount: true,
    refetchOnWindowFocus: true,
  });
  const data = query.data;

  return (
    <div className="space-y-5">
      <Breadcrumb
        items={[{ label: 'Companies', href: '/hierarchy/organisation' }, { label: data?.name ?? 'Company' }]}
        homeHref="/hierarchy"
        homeLabel="Platform Overview"
      />

      {query.isLoading ? (
        <LoadingSkeleton lines={8} />
      ) : query.isError || !data ? (
        <EmptyState
          icon={Building2}
          title="Couldn't load this company"
          description={query.error?.message ?? 'It may not exist.'}
        />
      ) : (
        <>
          <PageHeader
            title={data.name}
            description="Its teams and assigned staff. Open a team to see its people."
            actions={
              <div className="flex items-center gap-2">
                {data.archived ? <Badge variant="neutral">Archived</Badge> : null}
                <Button type="button" variant="outline" size="sm" onClick={() => exportBreakdownCsv(data)}>
                  <Download className="size-4" />
                  Export CSV
                </Button>
              </div>
            }
          />

          <div className="grid gap-4 sm:grid-cols-2">
            <StatTile icon={Building2} tone="primary" size="sm" label="Teams" value={n(data.teamCount)} />
            <StatTile icon={Users} tone="primary" size="sm" label="Employees" value={n(data.employeeCount)} />
          </div>

          <div className={cn(surface('subtle'), 'flex items-center gap-2 px-4 py-3 text-sm')}>
            <ShieldCheck className="size-4 shrink-0 text-muted-foreground" />
            <span className="text-muted-foreground">Company Admin:</span>
            <StaffName staff={data.companyAdmin} />
          </div>

          {data.teams.length === 0 ? (
            <EmptyState icon={Users} title="No teams yet" description="Teams appear here once they're created." />
          ) : (
            <div className="space-y-2">
              {data.teams.map((t) => (
                <Link
                  key={t.teamId}
                  href={`/hierarchy/organisation/${encodeURIComponent(companyId)}/${encodeURIComponent(t.teamId)}`}
                  className="block rounded-2xl focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background"
                >
                  <Card variant="interactive">
                    <CardContent className="flex flex-wrap items-center gap-x-4 gap-y-3 p-4">
                      <div className="min-w-0 flex-1">
                        <p className="truncate font-medium">{t.name}</p>
                        <p className="text-xs text-muted-foreground">
                          {n(t.employeeCount)} employee{t.employeeCount === 1 ? '' : 's'}
                        </p>
                      </div>
                      <dl className="flex flex-wrap gap-x-4 gap-y-1 text-xs">
                        <Slot label="HR" staff={t.hr} />
                        <Slot label="Manager" staff={t.manager} />
                        <Slot label="Accountant" staff={t.accountant} />
                      </dl>
                      <ChevronRight className="size-4 shrink-0 text-muted-foreground" aria-hidden />
                    </CardContent>
                  </Card>
                </Link>
              ))}
            </div>
          )}
        </>
      )}
    </div>
  );
}

/** A role slot on a team row: the assigned staff member's name, or a muted "Unassigned". */
function Slot({ label, staff }: { label: string; staff: StaffRef | null }) {
  return (
    <div className="min-w-0">
      <dt className="text-[10px] uppercase tracking-wide text-muted-foreground">{label}</dt>
      <dd className={cn('truncate', staff ? 'font-medium' : 'text-muted-foreground')}>
        {staff ? staff.name : 'Unassigned'}
      </dd>
    </div>
  );
}

/** A staff member (name), or a muted "—" when the slot is unassigned. */
function StaffName({ staff }: { staff: StaffRef | null }) {
  if (!staff) return <span className="text-muted-foreground">—</span>;
  return <span className="min-w-0 font-medium">{staff.name}</span>;
}

/** Staff as "Name (email)" for a CSV cell, or "—" when unassigned — org data, never employee PII. */
function staffText(staff: StaffRef | null): string {
  if (!staff) return '—';
  return staff.email ? `${staff.name} (${staff.email})` : staff.name;
}

/** One company's org breakdown: summary line + one row per team (Team, HR, Manager, Accountant, Employees). */
function exportBreakdownCsv(b: CompanyBreakdown): void {
  const summary = toCsv(
    ['Company', 'Status', 'Company Admin', 'Teams', 'Employees'],
    [[b.name, b.archived ? 'Archived' : 'Active', staffText(b.companyAdmin), b.teamCount, b.employeeCount]],
  );
  const teamsTable = toCsv(
    ['Team', 'HR', 'Manager', 'Accountant', 'Employees'],
    b.teams.map((t: TeamBreakdown) => [
      t.name,
      staffText(t.hr),
      staffText(t.manager),
      staffText(t.accountant),
      t.employeeCount,
    ]),
  );
  downloadCsv(`organisation_company_${b.companyId}_${istTodayIso()}.csv`, `${summary}\r\n\r\n${teamsTable}`);
}
