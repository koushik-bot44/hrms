'use client';

import type { EmployeeStatus } from '@/lib/contract';
import { getDashboardSummary } from '@/lib/api/dashboard';
import { useApiQuery } from '@/lib/api/hooks';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Progress } from '@/components/ui/progress';
import { StatusBadge } from '@/components/status-badge';

/** The employee's own onboarding progress summary (forms completed, status, next action, ID). */
export function OnboardingSummaryCard() {
  const query = useApiQuery(['dashboard'], getDashboardSummary, { refetchOnWindowFocus: true });
  const p = query.data?.employeeProgress;
  if (!p) return null;

  const pct = p.formsTotal ? (p.formsCompleted / p.formsTotal) * 100 : 0;

  return (
    <Card>
      <CardHeader className="flex-row items-center justify-between space-y-0">
        <CardTitle className="text-base">Your onboarding</CardTitle>
        <StatusBadge status={p.status as EmployeeStatus} />
      </CardHeader>
      <CardContent className="space-y-3">
        <div className="flex items-center justify-between text-sm">
          <span className="text-muted-foreground">Forms completed</span>
          <span className="tabular-nums">
            {p.formsCompleted} / {p.formsTotal}
          </span>
        </div>
        <Progress value={pct} />
        <p className="text-sm text-muted-foreground">{p.nextAction}</p>
        {p.employeeId ? (
          <p className="font-mono text-xs text-muted-foreground">Employee ID: {p.employeeId}</p>
        ) : null}
      </CardContent>
    </Card>
  );
}
