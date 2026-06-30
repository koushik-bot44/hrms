import type { Metadata } from 'next';
import { Activity, CheckCircle2, Clock, UserPlus } from 'lucide-react';
import { PageHeader } from '@/components/page-header';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { EmptyState } from '@/components/empty-state';
import { OnboardEmployeeDialog } from '@/components/hr/onboard-employee-dialog';

export const metadata: Metadata = { title: 'Dashboard' };

const STATS = [
  { label: 'Onboarded', icon: UserPlus },
  { label: 'Pending verification', icon: Clock },
  { label: 'Approved', icon: CheckCircle2 },
] as const;

export default function HrDashboardPage() {
  return (
    <div className="space-y-6">
      <PageHeader
        title="HR workspace"
        description="Onboard new employees and track their progress."
        actions={<OnboardEmployeeDialog />}
      />
      <div className="grid gap-4 sm:grid-cols-3">
        {STATS.map((s) => (
          <Card key={s.label}>
            <CardHeader className="flex-row items-center justify-between space-y-0 pb-2">
              <CardTitle className="text-sm font-medium text-muted-foreground">{s.label}</CardTitle>
              <s.icon className="size-4 text-muted-foreground" />
            </CardHeader>
            <CardContent>
              <div className="text-2xl font-semibold tabular-nums">—</div>
            </CardContent>
          </Card>
        ))}
      </div>
      <EmptyState
        icon={Activity}
        title="No recent activity"
        description="Onboarding events and verification updates will appear here."
      />
    </div>
  );
}
