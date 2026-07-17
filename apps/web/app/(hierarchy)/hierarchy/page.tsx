import type { Metadata } from 'next';
import { Building2, ClipboardCheck, LineChart, UserRound } from 'lucide-react';
import { PageHeader } from '@/components/page-header';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';

export const metadata: Metadata = { title: 'Platform Overview' };

/**
 * Placeholder Platform Overview for the HIERARCHY role (§2/§6) — cross-platform, read-only,
 * AGGREGATES-ONLY. This stage is the shell only: empty-state summary cards that later stages fill with
 * platform-wide counts/summaries. It reads no per-record/PII data (there is none to read here).
 */
const SECTIONS = [
  {
    icon: Building2,
    title: 'Companies',
    blurb: 'Total, active and archived companies across the platform.',
  },
  {
    icon: UserRound,
    title: 'Employees',
    blurb: 'Aggregate headcount and onboarding status — counts only, never individual records.',
  },
  {
    icon: ClipboardCheck,
    title: 'Onboarding & approvals',
    blurb: 'How many are in-flight, verified or approved, platform-wide.',
  },
  {
    icon: LineChart,
    title: 'Trends',
    blurb: 'Summary trends over time — added in a later stage.',
  },
] as const;

export default function HierarchyOverviewPage() {
  return (
    <div className="space-y-6">
      <PageHeader
        title="Platform Overview"
        description="Cross-platform, read-only summaries and counts. Individual records, PII, attendance and leave are never shown here."
      />

      <div className="rounded-md border border-dashed bg-muted/30 p-4 text-sm text-muted-foreground">
        Aggregate figures land in an upcoming stage. This overview shows platform-wide totals only — it
        never reaches an individual employee’s record.
      </div>

      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        {SECTIONS.map(({ icon: Icon, title, blurb }) => (
          <Card key={title}>
            <CardHeader className="flex-row items-center justify-between space-y-0 pb-2">
              <CardTitle className="text-sm font-medium text-muted-foreground">{title}</CardTitle>
              <Icon className="size-4 text-muted-foreground" aria-hidden />
            </CardHeader>
            <CardContent className="space-y-1">
              <div className="text-2xl font-semibold tabular-nums text-muted-foreground">—</div>
              <p className="text-xs text-muted-foreground">{blurb}</p>
            </CardContent>
          </Card>
        ))}
      </div>
    </div>
  );
}
