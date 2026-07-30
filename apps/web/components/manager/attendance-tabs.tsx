'use client';

import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { TeamAttendance } from '@/components/manager/team-attendance';
import { AttendanceActivityFeed } from '@/components/manager/attendance-activity-feed';
import { ManagerWorkLog } from '@/components/manager/work-log';

/**
 * The Manager's attendance area (§8a): the existing LIVE roster + pull-based activity feed, and — ALONGSIDE
 * it, not replacing it — the shared WORK LOG analytics for the manager's own team (the same components the
 * Accountant mounts; the accountant dual-mount pattern). Both lenses are scoped to the manager's team
 * server-side.
 */
export function ManagerAttendanceTabs() {
  return (
    <Tabs defaultValue="live" className="space-y-4">
      <TabsList>
        <TabsTrigger value="live">Live roster</TabsTrigger>
        <TabsTrigger value="worklog">Work Log</TabsTrigger>
      </TabsList>
      <TabsContent value="live">
        <div className="grid gap-6 lg:grid-cols-3">
          <div className="lg:col-span-2">
            <TeamAttendance />
          </div>
          <div className="lg:col-span-1">
            <AttendanceActivityFeed />
          </div>
        </div>
      </TabsContent>
      <TabsContent value="worklog">
        <ManagerWorkLog />
      </TabsContent>
    </Tabs>
  );
}
