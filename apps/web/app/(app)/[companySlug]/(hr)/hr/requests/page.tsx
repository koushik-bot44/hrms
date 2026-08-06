'use client';

import Link from 'next/link';
import { ChevronRight, Inbox } from 'lucide-react';
import { REQUEST_TYPE_LABELS } from '@/lib/contract';
import { getHrLetterRequests, requestKeys } from '@/lib/api/requests';
import { useApiQuery } from '@/lib/api/hooks';
import { useCompanyPath } from '@/lib/auth/use-company-path';
import { PageHeader } from '@/components/page-header';
import { EmptyState } from '@/components/empty-state';
import { LoadingSkeleton } from '@/components/loading-skeleton';
import { StatusBadge } from '@/components/status-badge';
import { Card, CardContent } from '@/components/ui/card';
import { surface } from '@/components/ui/surface';
import { cn } from '@/lib/utils';

/**
 * The HR "Requests" inbox (§3.6) — the offboarding-letter requests routed to this HR (pending first). Each row
 * deep-links to that employee's record, where the existing Offboarding panel issues the letter (no duplicated
 * dialog). The nav badge counts the open ones. Access is gated by the (hr) layout's RequireRole.
 */
export default function HrRequestsPage() {
  const cp = useCompanyPath();
  const { data, isLoading } = useApiQuery(requestKeys.hrLetters(), getHrLetterRequests);
  const rows = data?.requests ?? [];

  return (
    <div className="space-y-6">
      <PageHeader
        title="Requests"
        description="Relieving and experience letter requests from your employees. Open a record to issue the letter."
        editorial
      />
      {isLoading ? (
        <LoadingSkeleton lines={4} />
      ) : rows.length === 0 ? (
        <EmptyState
          icon={Inbox}
          title="No letter requests"
          description="When an employee requests a relieving or experience letter, it appears here."
        />
      ) : (
        <Card>
          <CardContent className="space-y-2 pt-6">
            <ul className="space-y-2">
              {rows.map((r) => (
                <li key={r.id}>
                  <Link
                    href={cp(`/hr/employees/${r.employeeId}`)}
                    className={cn(
                      surface('subtle'),
                      'flex flex-wrap items-center gap-3 p-3 transition-colors hover:bg-muted/50',
                    )}
                  >
                    <div className="min-w-0 flex-1">
                      <p className="truncate text-sm font-medium">
                        {r.employeeName ?? '—'}
                        {r.employeeCode ? (
                          <span className="ml-1.5 font-mono text-xs text-muted-foreground">
                            {r.employeeCode}
                          </span>
                        ) : null}
                      </p>
                      <p className="truncate text-xs text-muted-foreground">
                        {r.title ?? REQUEST_TYPE_LABELS[r.type]}
                        {r.requestedAt ? ` · requested ${new Date(r.requestedAt).toLocaleDateString()}` : ''}
                        {r.note ? ` · ${r.note}` : ''}
                      </p>
                    </div>
                    <StatusBadge status={r.status} />
                    <ChevronRight className="size-4 shrink-0 text-muted-foreground" aria-hidden />
                  </Link>
                </li>
              ))}
            </ul>
          </CardContent>
        </Card>
      )}
    </div>
  );
}
