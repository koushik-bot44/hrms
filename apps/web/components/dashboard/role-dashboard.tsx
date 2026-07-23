'use client';

import Link from 'next/link';
import { Activity, ArrowUpRight, Building2 } from 'lucide-react';
import type { ActivityItem, StatCard } from '@/lib/contract';
import { getDashboardSummary } from '@/lib/api/dashboard';
import { useApiQuery } from '@/lib/api/hooks';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { EmptyState } from '@/components/empty-state';
import { cn } from '@/lib/utils';

/** Where each stat card drills into — the existing filtered list route (reused, not new pages). */
const DRILL: Record<string, string> = {
  'super.companiesActive': '/super-admin',
  'super.companiesArchived': '/super-admin?view=archived',
  'ca.teams': '/company-admin#teams',
  'hr.onboarded': '/hr/employees',
  'hr.inProgress': '/hr/employees?status=IN_PROGRESS',
  'hr.pendingVerification': '/hr/employees?status=SUBMITTED',
  'hr.inRevision': '/hr/employees?status=REVISION_REQUESTED',
  'hr.approved': '/hr/employees?status=APPROVED',
  'hr.rejected': '/hr/employees?status=REJECTED',
  'manager.pendingApprovals': '/manager?tab=pending',
  'manager.approved': '/manager?tab=history&status=APPROVED',
  'manager.rejected': '/manager?tab=history&status=REJECTED',
  'manager.unreadNotifications': '/manager/notifications',
};

/**
 * Live, role-scoped dashboard: stat cards (clickable → filtered lists) + a recent-activity feed.
 * `show` renders only one section so a page can place them apart (e.g. stats above a list, activity
 * below it); both instances share the one deduped `['dashboard']` query.
 */
export function RoleDashboard({
  show = 'all',
  heroStats = false,
}: {
  show?: 'all' | 'stats' | 'activity';
  /** Render the stat cards as the mint "moment" (tinted surface, oversized number) — opt-in per page. */
  heroStats?: boolean;
}) {
  const query = useApiQuery(['dashboard'], getDashboardSummary, { refetchOnWindowFocus: true });
  const wantStats = show !== 'activity';
  const wantActivity = show !== 'stats';

  if (query.isLoading) {
    return (
      <div className="space-y-6">
        {wantStats ? (
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            {Array.from({ length: 4 }).map((_, i) => (
              <Skeleton key={i} className="h-24 w-full" />
            ))}
          </div>
        ) : null}
        {wantActivity ? <Skeleton className="h-40 w-full" /> : null}
      </div>
    );
  }

  if (query.isError || !query.data) {
    return (
      <EmptyState
        icon={Activity}
        title="Couldn't load your dashboard"
        description={query.error?.message ?? 'Please try again.'}
      />
    );
  }

  const { stats, recentActivity } = query.data;

  return (
    <div className="space-y-6">
      {wantStats && stats.length > 0 ? (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          {stats.map((s) => (
            <StatCardView key={s.key} card={s} href={DRILL[s.key]} hero={heroStats} />
          ))}
        </div>
      ) : null}

      {wantActivity ? (
        <section className="space-y-3">
          <h3 className="text-sm font-semibold text-muted-foreground">Recent activity</h3>
          {recentActivity.length === 0 ? (
            <EmptyState
              icon={Activity}
              title="No recent activity"
              description="Onboarding events and verification updates will appear here."
            />
          ) : (
            <ul className="divide-y divide-border rounded-md border">
              {recentActivity.map((item, i) => (
                <ActivityRow key={i} item={item} />
              ))}
            </ul>
          )}
        </section>
      ) : null}
    </div>
  );
}

function StatCardView({ card, href, hero = false }: { card: StatCard; href?: string; hero?: boolean }) {
  const inner = (
    <Card
      variant={hero ? 'tint' : href ? 'interactive' : 'default'}
      className={cn('h-full', !hero && href && 'transition-colors hover:border-primary/50')}
    >
      <CardHeader className="flex-row items-center justify-between space-y-0 pb-2">
        <CardTitle
          className={cn('text-sm font-medium', hero ? 'text-primary' : 'text-muted-foreground')}
        >
          {card.label}
        </CardTitle>
        {href ? <ArrowUpRight className="size-4 text-muted-foreground" aria-hidden /> : null}
      </CardHeader>
      <CardContent>
        {/* A 0 renders as "0" (never a placeholder). */}
        <div className={cn('font-semibold tabular-nums tracking-tight', hero ? 'text-4xl' : 'text-2xl')}>
          {card.value}
        </div>
      </CardContent>
    </Card>
  );
  return href ? (
    <Link href={href} className="block focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring rounded-lg">
      {inner}
    </Link>
  ) : (
    inner
  );
}

function ActivityRow({ item }: { item: ActivityItem }) {
  const meta = [item.actorName, item.company].filter(Boolean).join(' · ');
  return (
    <li className="flex items-center justify-between gap-3 px-3 py-2.5 text-sm">
      <div className="min-w-0">
        <p className="truncate">
          <span className="font-medium">{item.subjectName ?? '—'}</span>
          <span className="text-muted-foreground"> — {item.action}</span>
        </p>
        {meta ? (
          <p className="flex items-center gap-1 truncate text-xs text-muted-foreground">
            {item.company ? <Building2 className="size-3" aria-hidden /> : null}
            {meta}
          </p>
        ) : null}
      </div>
      <span className="shrink-0 whitespace-nowrap text-xs text-muted-foreground" title={item.at ?? ''}>
        {relativeTime(item.at)}
      </span>
    </li>
  );
}

function relativeTime(iso: string | undefined): string {
  if (!iso) return '';
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return '';
  const secs = Math.round((Date.now() - then) / 1000);
  if (secs < 60) return 'just now';
  const mins = Math.round(secs / 60);
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.round(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  const days = Math.round(hrs / 24);
  if (days < 30) return `${days}d ago`;
  return new Date(iso).toLocaleDateString();
}
