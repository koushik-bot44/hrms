'use client';

import * as React from 'react';
import { useRouter } from 'next/navigation';
import { ArrowLeft, ShieldCheck } from 'lucide-react';
import type { RevealedSensitive } from '@/lib/contract';
import { getAccountantRecord, revealAccountantSensitive } from '@/lib/api/accountant';
import { useApiMutation, useApiQuery } from '@/lib/api/hooks';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { EmptyState } from '@/components/empty-state';
import { RecordView } from '@/components/hr/record-view';

/** Read-only employee record for the Accountant (§2): masked by default, with an audited reveal. */
export function AccountantRecord({ employeeId }: { employeeId: string }) {
  const router = useRouter();
  const [revealed, setRevealed] = React.useState<RevealedSensitive | null>(null);

  const query = useApiQuery(
    ['accountant-record', employeeId],
    (signal) => getAccountantRecord(employeeId, signal),
    { retry: false },
  );

  const revealMutation = useApiMutation(() => revealAccountantSensitive(employeeId), {
    successMessage: 'Sensitive fields revealed (audited)',
    onSuccess: (data) => setRevealed(data),
  });

  if (query.isLoading) {
    return (
      <div className="space-y-4">
        <Skeleton className="h-20 w-full" />
        <Skeleton className="h-40 w-full" />
      </div>
    );
  }
  if (query.isError || !query.data) {
    return (
      <EmptyState
        icon={ShieldCheck}
        title="Couldn't open this record"
        description={
          query.error?.status === 404
            ? 'That employee is not approved (or no longer exists).'
            : query.error?.message ?? 'Please try again.'
        }
      />
    );
  }

  return (
    <div className="space-y-5">
      <Button variant="ghost" size="sm" onClick={() => router.push('/accountant')}>
        <ArrowLeft className="size-4" />
        Back to employees
      </Button>
      <RecordView
        record={query.data}
        editable={false}
        revealed={revealed}
        onReveal={() => revealMutation.mutate()}
      />
    </div>
  );
}
