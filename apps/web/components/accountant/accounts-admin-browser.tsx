'use client';

import * as React from 'react';
import { Building2, ChevronRight, Users2 } from 'lucide-react';
import type { ViewerCompanyRow, ViewerTeamRow } from '@/lib/contract';
import { getCompanyTeams, getViewerCompanies } from '@/lib/api/accountant';
import { useApiQuery } from '@/lib/api/hooks';
import { Card, CardContent } from '@/components/ui/card';
import { Breadcrumb, type Crumb } from '@/components/ui/breadcrumb';
import { EmptyState } from '@/components/empty-state';
import { LoadingSkeleton } from '@/components/loading-skeleton';
import { TeamViewTabs } from '@/components/accountant/team-view-tabs';

/**
 * The ACCOUNTS_ADMIN team-wise browser (§2): COMPANY → TEAM → EMPLOYEE. Cross-company, READ-ONLY. Pick a
 * company to see its teams (HR, Manager, approved count), drill into a team for its approved employees,
 * then open a read-only record. Breadcrumbs + Back navigate the drilldown.
 */
export function AccountsAdminBrowser() {
  const [company, setCompany] = React.useState<ViewerCompanyRow | null>(null);
  const [team, setTeam] = React.useState<ViewerTeamRow | null>(null);

  const toRoot = () => {
    setCompany(null);
    setTeam(null);
  };

  // Shared breadcrumb (home chip + Companies > {company} > {team}); the last crumb is the current page.
  const crumbs: Crumb[] = [{ label: 'Companies', onClick: toRoot }];
  if (company) crumbs.push({ label: company.name, onClick: () => setTeam(null) });
  if (team) crumbs.push({ label: team.name });

  return (
    <div className="space-y-5">
      <Breadcrumb items={crumbs} onHome={toRoot} homeLabel="Companies" />
      {!company ? (
        <CompanyList onPick={setCompany} />
      ) : !team ? (
        <TeamList company={company} onPick={setTeam} />
      ) : (
        <TeamViewTabs teamId={team.id} />
      )}
    </div>
  );
}

function CompanyList({ onPick }: { onPick: (c: ViewerCompanyRow) => void }) {
  const query = useApiQuery(['viewer-companies'], (signal) => getViewerCompanies(signal));

  if (query.isLoading) return <LoadingSkeleton lines={4} />;
  if (query.isError) {
    return (
      <EmptyState
        icon={Building2}
        title="Couldn't load companies"
        description={query.error?.message ?? 'Please try again.'}
      />
    );
  }
  const companies = query.data ?? [];
  if (companies.length === 0) {
    return <EmptyState icon={Building2} title="No companies" description="Nothing to browse yet." />;
  }
  return (
    <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
      {companies.map((c) => (
        <button key={c.id} type="button" onClick={() => onPick(c)} className="text-left">
          <Card variant="interactive" className="h-full transition-colors hover:border-primary/50">
            <CardContent className="flex items-center justify-between gap-3 p-4">
              <div className="min-w-0">
                <p className="truncate font-medium">{c.name}</p>
                <p className="text-xs text-muted-foreground">{c.code}</p>
                <p className="mt-1 text-xs text-muted-foreground">
                  {c.teamCount} team{c.teamCount === 1 ? '' : 's'} · {c.employeeCount} employee
                  {c.employeeCount === 1 ? '' : 's'}
                </p>
              </div>
              <ChevronRight className="size-5 shrink-0 text-muted-foreground" />
            </CardContent>
          </Card>
        </button>
      ))}
    </div>
  );
}

function TeamList({
  company,
  onPick,
}: {
  company: ViewerCompanyRow;
  onPick: (t: ViewerTeamRow) => void;
}) {
  const query = useApiQuery(['viewer-teams', company.id], (signal) => getCompanyTeams(company.id, signal));

  if (query.isLoading) return <LoadingSkeleton lines={4} />;
  if (query.isError) {
    return (
      <EmptyState
        icon={Users2}
        title="Couldn't load teams"
        description={query.error?.message ?? 'Please try again.'}
      />
    );
  }
  const teams = query.data ?? [];
  if (teams.length === 0) {
    return (
      <EmptyState icon={Users2} title="No teams" description={`${company.name} has no teams yet.`} />
    );
  }
  return (
    <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
      {teams.map((t) => (
        <button key={t.id} type="button" onClick={() => onPick(t)} className="text-left">
          <Card variant="interactive" className="h-full transition-colors hover:border-primary/50">
            <CardContent className="space-y-1 p-4">
              <div className="flex items-center justify-between gap-2">
                <p className="truncate font-medium">{t.name}</p>
                <ChevronRight className="size-5 shrink-0 text-muted-foreground" />
              </div>
              <p className="text-xs text-muted-foreground">HR: {t.hrName ?? '—'}</p>
              <p className="text-xs text-muted-foreground">Manager: {t.managerName ?? '—'}</p>
              <p className="pt-1 text-xs text-muted-foreground">
                {t.employeeCount} approved employee{t.employeeCount === 1 ? '' : 's'}
              </p>
            </CardContent>
          </Card>
        </button>
      ))}
    </div>
  );
}
