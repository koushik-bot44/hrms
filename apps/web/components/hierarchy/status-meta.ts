import type { OnboardingFunnel } from '@/lib/contract';

/** The seven onboarding-funnel keys (§2). */
export type FunnelKey = keyof OnboardingFunnel;

/**
 * Label + theme-aware colour for each onboarding status, shared by the funnel, the distribution donut
 * and the per-company by-status view so every visual agrees. The main path deepens in the brand
 * primary toward HR-verified, then approved is success-green; the two off-path states are amber/red.
 */
export const STATUS_META: Record<FunnelKey, { label: string; color: string }> = {
  invited: { label: 'Invited', color: 'hsl(var(--primary) / 0.35)' },
  inProgress: { label: 'In progress', color: 'hsl(var(--primary) / 0.55)' },
  submitted: { label: 'Submitted', color: 'hsl(var(--primary) / 0.75)' },
  hrVerified: { label: 'HR verified', color: 'hsl(var(--primary))' },
  approved: { label: 'Approved', color: 'hsl(var(--success))' },
  revisionRequested: { label: 'Revision requested', color: 'hsl(var(--warning))' },
  rejected: { label: 'Rejected', color: 'hsl(var(--destructive))' },
};

/** The main onboarding path, in order. */
export const MAIN_PATH: FunnelKey[] = ['invited', 'inProgress', 'submitted', 'hrVerified', 'approved'];
/** Off the main path — shown distinctly (not part of the linear funnel). */
export const OFF_PATH: FunnelKey[] = ['revisionRequested', 'rejected'];
export const ALL_STATUSES: FunnelKey[] = [...MAIN_PATH, ...OFF_PATH];

export function funnelTotal(funnel: OnboardingFunnel): number {
  return ALL_STATUSES.reduce((sum, k) => sum + funnel[k], 0);
}
