'use client';

import { BadgeCheck, IdCard, Users } from 'lucide-react';
import type { HierarchyTeamMember } from '@/lib/contract';
import { getHierarchyTeamMembers } from '@/lib/api/hierarchy';
import { useApiQuery } from '@/lib/api/hooks';
import { PageHeader } from '@/components/page-header';
import { Breadcrumb } from '@/components/ui/breadcrumb';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { EmptyState } from '@/components/empty-state';
import { LoadingSkeleton } from '@/components/loading-skeleton';
import { surface } from '@/components/ui/surface';
import { cn } from '@/lib/utils';

const ROLE_LABEL: Record<string, string> = {
  HR: 'HR',
  MANAGER: 'Manager',
  ACCOUNTANT: 'Accountant',
  EMPLOYEE: 'Employee',
};

/**
 * Organisation — Level 3 (§2 charter widening): a team's people. Its STAFF (HR/Manager/Accountant, named, with
 * their role) and its EMPLOYEES (name, employee code, designation) — grouped clearly. This is the widened view:
 * full name, employee code, designation and role ONLY. No email, phone, address, PAN/Aadhaar, salary,
 * documents, forms, attendance or leave ever appear here.
 */
export function OrganisationTeam({ companyId, teamId }: { companyId: string; teamId: string }) {
  const query = useApiQuery(['hierarchy-team-members', teamId], (s) => getHierarchyTeamMembers(teamId, s), {
    refetchOnMount: true,
    refetchOnWindowFocus: true,
  });
  const data = query.data;

  return (
    <div className="space-y-5">
      <Breadcrumb
        items={[
          { label: 'Companies', href: '/hierarchy/organisation' },
          {
            label: data?.companyName ?? 'Company',
            href: `/hierarchy/organisation/${encodeURIComponent(companyId)}`,
          },
          { label: data?.teamName ?? 'Team' },
        ]}
        homeHref="/hierarchy"
        homeLabel="Platform Overview"
      />

      {query.isLoading ? (
        <LoadingSkeleton lines={8} />
      ) : query.isError || !data ? (
        <EmptyState
          icon={Users}
          title="Couldn't load this team"
          description={query.error?.message ?? 'It may not exist.'}
        />
      ) : (
        <>
          <PageHeader
            title={data.teamName}
            description="The team's assigned staff and its employees — names, codes, designations and roles only."
          />

          {/* Staff */}
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2 text-base">
                <BadgeCheck className="size-4 text-muted-foreground" />
                Staff
              </CardTitle>
            </CardHeader>
            <CardContent>
              {data.staff.length === 0 ? (
                <p className="text-sm text-muted-foreground">No staff assigned to this team yet.</p>
              ) : (
                <div className="grid gap-2 sm:grid-cols-2">
                  {data.staff.map((m, i) => (
                    <div key={`${m.role}-${i}`} className={cn(surface('subtle'), 'flex items-center gap-3 p-3')}>
                      <span className="flex size-9 shrink-0 items-center justify-center rounded-full bg-surface-tint text-sm font-semibold text-primary">
                        {initial(m.fullName)}
                      </span>
                      <div className="min-w-0 flex-1">
                        <p className="truncate text-sm font-medium">{m.fullName}</p>
                      </div>
                      <Badge variant="neutral" className="shrink-0">
                        {ROLE_LABEL[m.role] ?? m.role}
                      </Badge>
                    </div>
                  ))}
                </div>
              )}
            </CardContent>
          </Card>

          {/* Employees */}
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2 text-base">
                <Users className="size-4 text-muted-foreground" />
                Employees
                <span className="text-sm font-normal text-muted-foreground">({data.members.length})</span>
              </CardTitle>
            </CardHeader>
            <CardContent>
              {data.members.length === 0 ? (
                <EmptyState
                  icon={Users}
                  title="No employees yet"
                  description="Employees appear here once this team's HR onboards them."
                />
              ) : (
                <div className="space-y-2">
                  {data.members.map((m, i) => (
                    <EmployeeRow key={`${m.employeeCode ?? m.fullName}-${i}`} member={m} />
                  ))}
                </div>
              )}
            </CardContent>
          </Card>
        </>
      )}
    </div>
  );
}

function EmployeeRow({ member }: { member: HierarchyTeamMember }) {
  return (
    <div className={cn(surface('subtle'), 'flex flex-wrap items-center gap-x-4 gap-y-1 p-3')}>
      <span className="flex size-9 shrink-0 items-center justify-center rounded-full bg-surface-tint text-sm font-semibold text-primary">
        {initial(member.fullName)}
      </span>
      <div className="min-w-0 flex-1">
        <p className="truncate text-sm font-medium">{member.fullName}</p>
        <p className="truncate text-xs text-muted-foreground">{member.designation ?? 'No designation'}</p>
      </div>
      <span className="inline-flex shrink-0 items-center gap-1.5 rounded-md border px-2 py-1 text-xs font-mono tabular-nums text-muted-foreground">
        <IdCard className="size-3.5" aria-hidden />
        {member.employeeCode ?? 'No ID yet'}
      </span>
    </div>
  );
}

/** The person's initial for the avatar chip. */
function initial(name: string): string {
  return (name.trim()[0] ?? '?').toUpperCase();
}
