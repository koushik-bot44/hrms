'use client';

import * as React from 'react';
import { useSearchParams } from 'next/navigation';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { ApprovalsInbox } from '@/components/manager/approvals-inbox';
import { ApprovalsHistory } from '@/components/manager/approvals-history';

/**
 * The Manager's approvals workspace lives on the same page as the dashboard cards, so those cards
 * deep-link here via the query string (?tab=pending | ?tab=history&status=APPROVED|REJECTED): we sync
 * the active tab + History filter from the URL and scroll into view when the URL points at us.
 */
export function ApprovalsWorkspace() {
  const searchParams = useSearchParams();
  const urlTab = searchParams.get('tab');
  const statusFilter = searchParams.get('status'); // APPROVED | REJECTED | null
  const [tab, setTab] = React.useState(urlTab === 'history' ? 'history' : 'pending');
  const ref = React.useRef<HTMLDivElement>(null);

  React.useEffect(() => {
    if (urlTab === 'history' || urlTab === 'pending') {
      setTab(urlTab);
      // Deep-linked from a dashboard card (same page) — bring the list into view.
      ref.current?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
  }, [urlTab, statusFilter]);

  return (
    <div ref={ref} className="scroll-mt-6">
      <Tabs value={tab} onValueChange={setTab} className="space-y-4">
        <TabsList>
          <TabsTrigger value="pending">Pending</TabsTrigger>
          <TabsTrigger value="history">History</TabsTrigger>
        </TabsList>
        <TabsContent value="pending">
          <ApprovalsInbox />
        </TabsContent>
        <TabsContent value="history">
          <ApprovalsHistory statusFilter={statusFilter} />
        </TabsContent>
      </Tabs>
    </div>
  );
}
