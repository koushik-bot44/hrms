'use client';

import * as React from 'react';
import { useSearchParams } from 'next/navigation';
import { ApprovalsHistory } from '@/components/manager/approvals-history';

/**
 * The Manager's READ-ONLY team-onboarding history (§3.3). Approval authority now sits with HR, so there is
 * no pending inbox or approve/reject action — just who joined the team and when. Dashboard cards deep-link
 * here with an optional {@code ?status=APPROVED|REJECTED} filter; we scroll into view when pointed at.
 */
export function ApprovalsWorkspace() {
  const searchParams = useSearchParams();
  const statusFilter = searchParams.get('status'); // APPROVED | REJECTED | null
  const ref = React.useRef<HTMLDivElement>(null);

  React.useEffect(() => {
    if (statusFilter) ref.current?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }, [statusFilter]);

  return (
    <div ref={ref} className="scroll-mt-6">
      <ApprovalsHistory statusFilter={statusFilter} />
    </div>
  );
}
