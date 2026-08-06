import type { Metadata } from 'next';
import { PageHeader } from '@/components/page-header';
import { TeamLeave } from '@/components/manager/team-leave';

export const metadata: Metadata = { title: 'Leave' };

/** Manager leave approvals (§8b): the requests routed to this Manager — approve or reject. */
export default function ManagerLeavePage() {
  return (
    <div className="space-y-6">
      <PageHeader
        title="Leave"
        description="Time-off requests from your team — approve or reject. Rejections need a note."
      />
      <TeamLeave />
    </div>
  );
}
