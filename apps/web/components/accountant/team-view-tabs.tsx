'use client';

import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { TeamEmployeesTable } from '@/components/accountant/team-employees-table';
import { TeamAttendance } from '@/components/accountant/team-attendance';

/**
 * The two read-only lenses on a team (§2/§8a): the existing employee ROSTER and the ATTENDANCE dashboard —
 * both scoped to {@code teamId} server-side. Reused by ACCOUNTS_ADMIN (via company→team) and the ACCOUNTANT
 * (own team). Read-only throughout.
 */
export function TeamViewTabs({ teamId }: { teamId: string }) {
  return (
    <Tabs defaultValue="employees" className="space-y-4">
      <TabsList>
        <TabsTrigger value="employees">Employees</TabsTrigger>
        <TabsTrigger value="attendance">Work Log</TabsTrigger>
      </TabsList>
      <TabsContent value="employees">
        <TeamEmployeesTable teamId={teamId} />
      </TabsContent>
      <TabsContent value="attendance">
        <TeamAttendance teamId={teamId} />
      </TabsContent>
    </Tabs>
  );
}
