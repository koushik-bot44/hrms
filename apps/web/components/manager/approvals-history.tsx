'use client';

import { History, UserCheck } from 'lucide-react';
import type { Approval } from '@/lib/contract';
import { getApprovalHistory } from '@/lib/api/manager';
import { useApiQuery } from '@/lib/api/hooks';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { EmptyState } from '@/components/empty-state';
import { StatusBadge } from '@/components/status-badge';

const KEY = ['manager-approvals-history'] as const;

export function ApprovalsHistory() {
  const query = useApiQuery(KEY, getApprovalHistory);

  if (query.isLoading) {
    return (
      <div className="space-y-3">
        <Skeleton className="h-24 w-full" />
        <Skeleton className="h-24 w-full" />
      </div>
    );
  }

  const items: Approval[] = query.data ?? [];
  if (items.length === 0) {
    return (
      <EmptyState
        icon={History}
        title="No decisions yet"
        description="Employees you approve or reject will appear here."
      />
    );
  }

  return (
    <div className="space-y-3">
      {items.map((a) => (
        <Card key={a.id}>
          <CardHeader className="flex-row items-start justify-between gap-3 space-y-0">
            <div className="min-w-0 space-y-1">
              <CardTitle className="font-mono text-base">{a.employeeCode}</CardTitle>
              <p className="truncate text-sm text-muted-foreground">{a.employeeEmail}</p>
            </div>
            <StatusBadge status={a.status} />
          </CardHeader>
          <CardContent className="space-y-2 pt-0">
            <div className="flex flex-wrap items-center justify-between gap-2 text-xs text-muted-foreground">
              <span className="flex items-center gap-1.5">
                <UserCheck className="size-3.5" aria-hidden />
                Onboarded by {a.hrName ?? 'HR'}
              </span>
              {a.decidedAt ? <span>Decided {new Date(a.decidedAt).toLocaleString()}</span> : null}
            </div>
            {a.status === 'REJECTED' && a.note ? (
              <p className="rounded-md border border-destructive/30 bg-destructive/5 px-3 py-2 text-sm">
                <span className="font-medium">Reason: </span>
                {a.note}
              </p>
            ) : null}
          </CardContent>
        </Card>
      ))}
    </div>
  );
}
