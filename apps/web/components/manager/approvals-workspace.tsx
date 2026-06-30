'use client';

import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { ApprovalsInbox } from '@/components/manager/approvals-inbox';
import { ApprovalsHistory } from '@/components/manager/approvals-history';

export function ApprovalsWorkspace() {
  return (
    <Tabs defaultValue="pending" className="space-y-4">
      <TabsList>
        <TabsTrigger value="pending">Pending</TabsTrigger>
        <TabsTrigger value="history">History</TabsTrigger>
      </TabsList>
      <TabsContent value="pending">
        <ApprovalsInbox />
      </TabsContent>
      <TabsContent value="history">
        <ApprovalsHistory />
      </TabsContent>
    </Tabs>
  );
}
