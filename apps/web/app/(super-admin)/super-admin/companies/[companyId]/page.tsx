'use client';

import Link from 'next/link';
import { ArrowLeft, Building2, ShieldCheck, UserRound, Users } from 'lucide-react';
import { getCompany } from '@/lib/api/companies';
import { useApiQuery } from '@/lib/api/hooks';
import { companyIdFromParam } from '@/lib/company-url';
import { PageHeader } from '@/components/page-header';
import { Breadcrumb } from '@/components/ui/breadcrumb';
import { LoadingSkeleton } from '@/components/loading-skeleton';
import { EmptyState } from '@/components/empty-state';
import { CompanyStatusBadge } from '@/components/super-admin/company-status-badge';
import { ProvisionAdminForm } from '@/components/super-admin/provision-admin-form';
import { CompanyTeamsSection } from '@/components/super-admin/company-teams-section';
import { CompanyEmployeesSection } from '@/components/super-admin/company-employees-section';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';

export default function CompanyDetailPage({ params }: { params: { companyId: string } }) {
  const id = companyIdFromParam(params.companyId);
  const { data, isLoading, isError, error } = useApiQuery(['company', id], (signal) =>
    getCompany(id, signal),
  );

  const backLink = (
    <Button asChild variant="ghost" size="sm" className="-ml-2 w-fit text-muted-foreground">
      <Link href="/super-admin">
        <ArrowLeft className="size-4" />
        Companies
      </Link>
    </Button>
  );

  if (isLoading) {
    return (
      <div className="space-y-6">
        {backLink}
        <LoadingSkeleton lines={2} />
        <div className="grid gap-4 sm:grid-cols-2">
          <LoadingSkeleton lines={3} />
          <LoadingSkeleton lines={3} />
        </div>
      </div>
    );
  }

  if (isError || !data) {
    return (
      <div className="space-y-6">
        {backLink}
        <EmptyState
          icon={Building2}
          title="Company not found"
          description={error?.message ?? 'It may have been removed.'}
          action={
            <Button asChild size="sm">
              <Link href="/super-admin">Back to companies</Link>
            </Button>
          }
        />
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <Breadcrumb
        homeHref="/super-admin"
        homeLabel="Companies"
        items={[{ label: data.name }]}
      />
      <PageHeader
        title={data.name}
        description={`Code ${data.code} · Mail domain @${data.mailDomain}`}
        actions={<CompanyStatusBadge status={data.status} />}
      />

      <div className="grid gap-4 sm:grid-cols-2">
        <StatCard icon={Users} label="Teams" value={data.teamCount} />
        <StatCard icon={UserRound} label="Employees" value={data.employeeCount} />
      </div>

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-base">
            <ShieldCheck className="size-4 text-muted-foreground" />
            Company Admin
          </CardTitle>
          <CardDescription>
            One admin per company. They create teams and assign HR + Managers.
          </CardDescription>
        </CardHeader>
        <CardContent>
          {data.admin ? (
            <div className="flex flex-wrap items-center justify-between gap-3 rounded-md border bg-muted/30 p-4">
              <div className="space-y-0.5">
                <div className="font-medium">{data.admin.name}</div>
                <div className="text-sm text-muted-foreground">{data.admin.email}</div>
              </div>
              <Badge variant="success">{data.admin.status}</Badge>
            </div>
          ) : (
            <ProvisionAdminForm companyId={data.id} mailDomain={data.mailDomain} />
          )}
        </CardContent>
      </Card>

      <CompanyTeamsSection companyId={data.id} companyName={data.name} />

      <CompanyEmployeesSection companyId={data.id} companyName={data.name} />
    </div>
  );
}

function StatCard({
  icon: Icon,
  label,
  value,
}: {
  icon: React.ComponentType<{ className?: string }>;
  label: string;
  value: number;
}) {
  return (
    <Card>
      <CardHeader className="flex-row items-center justify-between space-y-0 pb-2">
        <CardTitle className="text-sm font-medium text-muted-foreground">{label}</CardTitle>
        <Icon className="size-4 text-muted-foreground" />
      </CardHeader>
      <CardContent>
        <div className="text-2xl font-semibold tabular-nums">{value}</div>
      </CardContent>
    </Card>
  );
}
