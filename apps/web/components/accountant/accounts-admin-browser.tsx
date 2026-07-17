'use client';

import * as React from 'react';
import { Building2, ChevronRight, Users2 } from 'lucide-react';
import type { ViewerCompanyRow, ViewerTeamRow } from '@/lib/contract';
import { getCompanyTeams, getViewerCompanies } from '@/lib/api/accountant';
import { useApiQuery } from '@/lib/api/hooks';
import { Card, CardContent } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
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

  return (
    <div className="space-y-4">
      <Breadcrumbs
        company={company}
        team={team}
        onRoot={() => {
          setCompany(null);
          setTeam(null);
        }}
        onCompany={() => setTeam(null)}
      />
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

function Breadcrumbs({
  company,
  team,
  onRoot,
  onCompany,
}: {
  company: ViewerCompanyRow | null;
  team: ViewerTeamRow | null;
  onRoot: () => void;
  onCompany: () => void;
}) {
  return (
    <nav className="flex flex-wrap items-center gap-1 text-sm" aria-label="Breadcrumb">
      <Crumb label="Companies" active={!company} onClick={onRoot} />
      {company ? (
        <>
          <ChevronRight className="size-4 text-muted-foreground" />
          <Crumb label={company.name} active={!team} onClick={onCompany} />
        </>
      ) : null}
      {team ? (
        <>
          <ChevronRight className="size-4 text-muted-foreground" />
          <span className="font-medium text-foreground">{team.name}</span>
        </>
      ) : null}
    </nav>
  );
}

function Crumb({ label, active, onClick }: { label: string; active: boolean; onClick: () => void }) {
  if (active) return <span className="font-medium text-foreground">{label}</span>;
  return (
    <button type="button" onClick={onClick} className="text-muted-foreground hover:text-primary hover:underline">
      {label}
    </button>
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
          <Card className="transition-colors hover:border-primary/50">
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
          <Card className="transition-colors hover:border-primary/50">
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
