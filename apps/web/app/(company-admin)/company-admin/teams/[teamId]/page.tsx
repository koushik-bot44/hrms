'use client';

import Link from 'next/link';
import { ArrowLeft, Users } from 'lucide-react';
import type { TeamMember, TeamRole } from '@/lib/contract';
import { getTeam } from '@/lib/api/teams';
import { useApiQuery } from '@/lib/api/hooks';
import { PageHeader } from '@/components/page-header';
import { LoadingSkeleton } from '@/components/loading-skeleton';
import { EmptyState } from '@/components/empty-state';
import { TeamStatusBadge } from '@/components/company-admin/team-status-badge';
import { AssignMemberDialog } from '@/components/company-admin/assign-member-dialog';
import { DeleteTeamDialog } from '@/components/company-admin/delete-team-dialog';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';

export default function TeamDetailPage({ params }: { params: { teamId: string } }) {
  const id = params.teamId;
  const { data, isLoading, isError, error } = useApiQuery(['team', id], (signal) =>
    getTeam(id, undefined, signal),
  );

  const backLink = (
    <Button asChild variant="ghost" size="sm" className="-ml-2 w-fit text-muted-foreground">
      <Link href="/company-admin">
        <ArrowLeft className="size-4" />
        Teams
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
          icon={Users}
          title="Team not found"
          description={error?.message ?? 'It may have been removed.'}
          action={
            <Button asChild size="sm">
              <Link href="/company-admin">Back to teams</Link>
            </Button>
          }
        />
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {backLink}
      <PageHeader
        title={data.name}
        description="One HR, one Manager and one Accountant run each team."
        actions={
          <div className="flex items-center gap-2">
            <TeamStatusBadge complete={data.complete} />
            <DeleteTeamDialog teamId={data.id} teamName={data.name} />
          </div>
        }
      />

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        <SlotCard title="HR" role="HR" member={data.hr} teamId={data.id} />
        <SlotCard title="Manager" role="MANAGER" member={data.manager} teamId={data.id} />
        <SlotCard title="Accountant" role="ACCOUNTANT" member={data.accountant} teamId={data.id} />
      </div>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">Members</CardTitle>
        </CardHeader>
        <CardContent>
          {data.members.length > 0 ? (
            <ul className="divide-y">
              {data.members.map((member) => (
                <li key={member.id} className="flex items-center justify-between py-3 first:pt-0">
                  <div className="space-y-0.5">
                    <div className="font-medium">{member.name}</div>
                    <div className="text-sm text-muted-foreground">{member.email}</div>
                  </div>
                  <Badge variant="primarySoft">{member.role}</Badge>
                </li>
              ))}
            </ul>
          ) : (
            <p className="text-sm text-muted-foreground">No members yet — assign an HR and Manager.</p>
          )}
        </CardContent>
      </Card>
    </div>
  );
}

function SlotCard({
  title,
  role,
  member,
  teamId,
}: {
  title: string;
  role: TeamRole;
  member: TeamMember | null;
  teamId: string;
}) {
  return (
    <Card>
      <CardHeader className="flex-row items-center justify-between space-y-0">
        <CardTitle className="text-base">{title}</CardTitle>
        {member ? <AssignMemberDialog teamId={teamId} role={role} label="Change" /> : null}
      </CardHeader>
      <CardContent>
        {member ? (
          <div className="space-y-0.5">
            <div className="font-medium">{member.name}</div>
            <div className="text-sm text-muted-foreground">{member.email}</div>
          </div>
        ) : (
          <div className="flex flex-col items-start gap-3">
            <p className="text-sm text-muted-foreground">No {title} assigned yet.</p>
            <AssignMemberDialog teamId={teamId} role={role} label={`Assign ${title}`} />
          </div>
        )}
      </CardContent>
    </Card>
  );
}
