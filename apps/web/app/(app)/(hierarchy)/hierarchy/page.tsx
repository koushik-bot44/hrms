import type { Metadata } from 'next';
import { PlatformOverview } from '@/components/hierarchy/platform-overview';

export const metadata: Metadata = { title: 'Platform Overview' };

/**
 * The HIERARCHY landing (§2): the read-only, cross-platform Platform Overview — totals, onboarding
 * funnel + distribution, monthly trends, employees-per-company + org drill-down, and ops metric cards.
 * Guarded to HIERARCHY by the (hierarchy) layout; all data comes from the aggregates-only
 * /hierarchy/** endpoints (no employee PII).
 */
export default function HierarchyOverviewPage() {
  return <PlatformOverview />;
}
