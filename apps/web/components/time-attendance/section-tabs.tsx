'use client';

import * as React from 'react';
import Link from 'next/link';
import { usePathname, useSearchParams } from 'next/navigation';
import {
  BarChart3,
  CalendarClock,
  FileSpreadsheet,
  LayoutDashboard,
  MailWarning,
  Radio,
  Router,
  Users,
} from 'lucide-react';
import { cn } from '@/lib/utils';

const ROOT = '/super-admin/time-attendance';

interface Section {
  label: string;
  href: string;
  icon: React.ComponentType<{ className?: string }>;
  /** P2 — rendered as an inert pill, never a link. */
  disabled?: boolean;
}

/**
 * The console's section bar.
 *
 * The three P2 sections are rendered as visible-but-inert pills rather than omitted. An operator who
 * can see that Reports exists and is not built yet asks a different question from one who assumes the
 * product simply cannot do it — and the placeholder is what stops "where are my reports?" arriving as
 * a bug report. They are `<span>`s, not disabled `<a>`s, so keyboard focus skips them entirely.
 */
const SECTIONS: Section[] = [
  { label: 'Overview', href: ROOT, icon: LayoutDashboard },
  { label: 'Live board', href: `${ROOT}/live`, icon: Radio },
  { label: 'People', href: `${ROOT}/people`, icon: Users },
  { label: 'Devices', href: `${ROOT}/devices`, icon: Router },
  { label: 'Reports', href: '', icon: BarChart3, disabled: true },
  { label: 'Regularisation', href: '', icon: CalendarClock, disabled: true },
  { label: 'Payroll export', href: '', icon: FileSpreadsheet, disabled: true },
];

export function SectionTabs() {
  const pathname = usePathname();
  const params = useSearchParams();
  // Carry the site scope across sections — losing it on every tab click would make the picker useless.
  const site = params.get('site');
  const qs = site ? `?site=${encodeURIComponent(site)}` : '';

  return (
    <nav aria-label="Time and attendance sections" className="flex flex-wrap items-center gap-2">
      {SECTIONS.map(({ label, href, icon: Icon, disabled }) => {
        const active =
          !disabled && (href === ROOT ? pathname === ROOT : pathname.startsWith(href));
        const className = cn(
          'inline-flex h-10 items-center gap-2 rounded-full border px-4 text-sm font-medium transition-colors',
          disabled
            ? 'cursor-default border-dashed border-border bg-transparent text-muted-foreground/60'
            : active
              ? 'border-transparent bg-primary text-primary-foreground shadow-sm'
              : 'border-border bg-card text-muted-foreground hover:bg-accent hover:text-accent-foreground',
        );

        if (disabled) {
          return (
            <span key={label} className={className} title="Coming in P2">
              <Icon className="size-4" />
              {label}
              <span className="rounded-full bg-muted px-1.5 py-0.5 text-[10px] uppercase tracking-wide">
                P2
              </span>
            </span>
          );
        }

        return (
          <Link
            key={label}
            href={`${href}${qs}`}
            aria-current={active ? 'page' : undefined}
            className={className}
          >
            <Icon className="size-4" />
            {label}
          </Link>
        );
      })}
    </nav>
  );
}

/** Shared banner for the "attribution is failing right now" count, linking into the People inbox. */
export function InboxAlert({ count, siteId }: { count: number; siteId: string | null }) {
  if (count <= 0) return null;
  const qs = siteId ? `?site=${encodeURIComponent(siteId)}` : '';
  return (
    <Link
      href={`${ROOT}/people${qs}#inbox`}
      className="flex items-center gap-3 rounded-xl border border-warning/20 bg-warning/10 px-4 py-3 text-sm text-warning transition-colors hover:bg-warning/15"
    >
      <MailWarning className="size-4 shrink-0" />
      <span>
        <strong className="font-semibold">{count}</strong>{' '}
        {count === 1 ? 'pin is' : 'pins are'} punching right now with nobody to attribute it to.
      </span>
    </Link>
  );
}
