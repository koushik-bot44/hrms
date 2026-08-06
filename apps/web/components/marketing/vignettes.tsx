import * as React from 'react';
import {
  Archive,
  CalendarDays,
  CheckCircle2,
  Clock,
  FileSignature,
  Paperclip,
  PenLine,
  Star,
  Timer,
} from 'lucide-react';
import { cn } from '@/lib/utils';

/**
 * Stylized, in-code UI vignettes for the marketing page — browser-framed compositions built from the app's
 * real design tokens so they read as intentional ILLUSTRATIONS of the product, not screenshots. Every label
 * is an OBVIOUSLY GENERIC placeholder (Acme Corp, Design Team, A. Sharma, EMP-0042, someone@company.example) —
 * invented for illustration, never a claim and never real internal data. Pure presentational server
 * components (no hooks, no data, no network).
 */

// ── shared bits ──────────────────────────────────────────────────────────────────────────────────────
function Avatar({ initials, className }: { initials: string; className?: string }) {
  return (
    <span
      className={cn(
        'inline-flex size-7 shrink-0 items-center justify-center rounded-full bg-primary/20 text-[11px] font-semibold text-primary-bright ring-1 ring-inset ring-primary/30',
        className,
      )}
    >
      {initials}
    </span>
  );
}

function Chip({
  children,
  tone = 'muted',
}: {
  children: React.ReactNode;
  tone?: 'muted' | 'primary' | 'success' | 'warning';
}) {
  const tones: Record<string, string> = {
    muted: 'bg-white/5 text-muted-foreground ring-white/10',
    primary: 'bg-primary/15 text-primary-bright ring-primary/30',
    success: 'bg-success/15 text-success ring-success/30',
    warning: 'bg-warning/15 text-warning ring-warning/30',
  };
  return (
    <span className={cn('rounded-full px-2 py-0.5 text-[10px] font-medium ring-1 ring-inset', tones[tone])}>
      {children}
    </span>
  );
}

// ── 1. Flagship dashboard (hero) ─────────────────────────────────────────────────────────────────────
export function DashboardVignette() {
  const bars = [52, 68, 61, 74, 83, 40, 12];
  const days = ['M', 'T', 'W', 'T', 'F', 'S', 'S'];
  return (
    <div className="grid grid-cols-[auto_1fr] gap-4 text-sm">
      {/* mini sidebar rail */}
      <div className="hidden w-11 flex-col items-center gap-3 rounded-lg bg-black/30 py-3 ring-1 ring-inset ring-white/5 sm:flex">
        <span className="size-6 rounded-md bg-primary/80" />
        {[CalendarDays, Clock, FileSignature, Archive].map((Icon, i) => (
          <span
            key={i}
            className={cn(
              'flex size-7 items-center justify-center rounded-md',
              i === 0 ? 'bg-primary/20 text-primary-bright' : 'text-muted-foreground',
            )}
          >
            <Icon className="size-4" />
          </span>
        ))}
      </div>

      <div className="min-w-0 space-y-3">
        <div className="flex items-center justify-between">
          <div>
            <div className="text-[11px] text-muted-foreground">Acme Corp · Overview</div>
            <div className="font-semibold text-foreground">Today at a glance</div>
          </div>
          <Chip tone="primary">Aug 2026</Chip>
        </div>

        {/* stat tiles */}
        <div className="grid grid-cols-3 gap-2">
          {[
            { k: 'Present', v: '92%', tone: 'success' as const },
            { k: 'On leave', v: '4', tone: 'warning' as const },
            { k: 'Pending', v: '7', tone: 'primary' as const },
          ].map((s) => (
            <div key={s.k} className="rounded-lg bg-white/[0.03] p-2.5 ring-1 ring-inset ring-white/5">
              <div className="text-[10px] uppercase tracking-wide text-muted-foreground">{s.k}</div>
              <div className={cn('mt-1 text-lg font-semibold text-foreground')}>{s.v}</div>
            </div>
          ))}
        </div>

        {/* bar chart */}
        <div className="rounded-lg bg-white/[0.03] p-3 ring-1 ring-inset ring-white/5">
          <div className="mb-2 flex items-center justify-between text-[11px] text-muted-foreground">
            <span>Attendance this week</span>
            <span className="text-success">▲ 6%</span>
          </div>
          <div className="flex h-16 items-end gap-1.5">
            {bars.map((h, i) => (
              <div key={i} className="flex flex-1 flex-col items-center gap-1">
                <div
                  className="w-full rounded-t bg-gradient-to-t from-primary/40 to-primary-bright"
                  style={{ height: `${h}%` }}
                />
                <span className="text-[9px] text-muted-foreground">{days[i]}</span>
              </div>
            ))}
          </div>
        </div>

        {/* activity */}
        <div className="space-y-1.5">
          {[
            { who: 'A. Sharma', act: 'signed the Offer Letter', tone: 'success' as const },
            { who: 'R. Iyer', act: 'submitted onboarding forms', tone: 'primary' as const },
          ].map((r) => (
            <div
              key={r.who}
              className="flex items-center gap-2.5 rounded-lg bg-white/[0.02] px-2.5 py-2 ring-1 ring-inset ring-white/5"
            >
              <Avatar initials={r.who.slice(0, 1) + r.who.split(' ')[1][0]} />
              <div className="min-w-0 flex-1 truncate text-[12px] text-foreground">
                <span className="font-medium">{r.who}</span>{' '}
                <span className="text-muted-foreground">{r.act}</span>
              </div>
              <Chip tone={r.tone}>{r.tone === 'success' ? 'Done' : 'New'}</Chip>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

// ── 2. Inline-fill document + signature line ─────────────────────────────────────────────────────────
export function InlineDocVignette() {
  return (
    <div className="space-y-3 text-[12px] leading-relaxed">
      <div className="flex items-center justify-between">
        <div className="font-semibold text-foreground">Offer Letter — Acme Corp</div>
        <Chip tone="warning">To sign</Chip>
      </div>
      <p className="text-muted-foreground">
        This letter confirms your appointment as{' '}
        <span className="rounded bg-primary/10 px-1.5 py-0.5 font-medium text-primary-bright ring-1 ring-inset ring-primary/25">
          Product Designer
        </span>{' '}
        with a date of joining of{' '}
        <span className="rounded bg-primary/10 px-1.5 py-0.5 font-medium text-primary-bright ring-1 ring-inset ring-primary/25">
          01 Sep 2026
        </span>
        , subject to the terms below.
      </p>
      <p className="text-muted-foreground">
        Please review the terms and sign below to accept.
      </p>
      <div className="flex items-end justify-between rounded-lg bg-white/[0.03] p-3 ring-1 ring-inset ring-white/5">
        <div>
          <div className="mb-1 text-[10px] uppercase tracking-wide text-muted-foreground">Signature</div>
          <svg viewBox="0 0 160 40" className="h-9 w-40 text-primary-bright" aria-hidden>
            <path
              d="M4 30 C 24 6, 34 6, 44 24 S 70 40, 84 18 S 112 4, 126 24 S 150 30, 156 16"
              fill="none"
              stroke="currentColor"
              strokeWidth={2}
              strokeLinecap="round"
            />
          </svg>
        </div>
        <span className="inline-flex items-center gap-1 text-[11px] text-success">
          <CheckCircle2 className="size-3.5" /> Signed
        </span>
      </div>
      <div className="flex items-center gap-2 text-[11px] text-muted-foreground">
        <PenLine className="size-3.5 text-primary-bright" /> Draw · Type · Upload · Upload &amp; clean
      </div>
    </div>
  );
}

// ── 3. Internal mail thread ──────────────────────────────────────────────────────────────────────────
export function MailVignette() {
  const rows = [
    { from: 'People Ops', sub: 'Your onboarding checklist', chip: 'Inbox', star: true, initials: 'PO' },
    { from: 'A. Sharma', sub: 'Re: Design review — Thu', chip: 'CC', star: false, initials: 'AS' },
    { from: 'Payroll', sub: 'Payslip · July', chip: 'Label', star: false, initials: 'Py' },
  ];
  return (
    <div className="space-y-2 text-[12px]">
      <div className="flex items-center justify-between">
        <div className="font-semibold text-foreground">Mail — someone@company.example</div>
        <Chip tone="primary">3 new</Chip>
      </div>
      {rows.map((r) => (
        <div
          key={r.sub}
          className="flex items-center gap-2.5 rounded-lg bg-white/[0.03] px-2.5 py-2 ring-1 ring-inset ring-white/5"
        >
          <Star className={cn('size-3.5', r.star ? 'fill-warning text-warning' : 'text-muted-foreground')} />
          <Avatar initials={r.initials} />
          <div className="min-w-0 flex-1">
            <div className="truncate font-medium text-foreground">{r.from}</div>
            <div className="truncate text-[11px] text-muted-foreground">{r.sub}</div>
          </div>
          <Paperclip className="size-3 text-muted-foreground" />
          <Chip>{r.chip}</Chip>
        </div>
      ))}
      <div className="flex items-center gap-2 pt-1 text-[11px] text-muted-foreground">
        <Archive className="size-3.5" /> Archive · Drafts · Labels · Filters — private to your organization
      </div>
    </div>
  );
}

// ── 4. Attendance / clock ────────────────────────────────────────────────────────────────────────────
export function AttendanceVignette() {
  return (
    <div className="space-y-3 text-[12px]">
      <div className="flex items-center justify-between">
        <div className="font-semibold text-foreground">Attendance</div>
        <Chip tone="success">Clocked in</Chip>
      </div>
      <div className="grid grid-cols-2 gap-2">
        <div className="rounded-lg bg-white/[0.03] p-3 ring-1 ring-inset ring-white/5">
          <div className="flex items-center gap-1.5 text-[10px] uppercase tracking-wide text-muted-foreground">
            <Clock className="size-3.5" /> Since
          </div>
          <div className="mt-1 text-lg font-semibold tabular-nums text-foreground">09:12</div>
        </div>
        <div className="rounded-lg bg-white/[0.03] p-3 ring-1 ring-inset ring-white/5">
          <div className="flex items-center gap-1.5 text-[10px] uppercase tracking-wide text-muted-foreground">
            <Timer className="size-3.5" /> Break
          </div>
          <div className="mt-1 text-lg font-semibold tabular-nums text-foreground">00:24</div>
        </div>
      </div>
      <div className="rounded-lg bg-white/[0.03] p-3 ring-1 ring-inset ring-white/5">
        <div className="mb-1.5 flex items-center justify-between text-[11px] text-muted-foreground">
          <span>Adherence · payroll cycle</span>
          <span className="text-foreground">96%</span>
        </div>
        <div className="h-2 w-full overflow-hidden rounded-full bg-white/10">
          <div className="h-full w-[96%] rounded-full bg-gradient-to-r from-primary to-primary-bright" />
        </div>
        <div className="mt-2 flex gap-2 text-[10px] text-muted-foreground">
          <Chip>Today</Chip>
          <Chip>Month</Chip>
          <Chip tone="primary">Payroll cycle</Chip>
          <Chip>Custom</Chip>
        </div>
      </div>
    </div>
  );
}

// ── 5. Offboarding clearance checklist ───────────────────────────────────────────────────────────────
export function ClearanceVignette() {
  const items = [
    { k: 'Assets returned', done: true },
    { k: 'Access revoked', done: true },
    { k: 'Finance settled', done: false },
    { k: 'Exit interview', done: false },
  ];
  return (
    <div className="space-y-3 text-[12px]">
      <div className="flex items-center justify-between">
        <div className="font-semibold text-foreground">Exit clearance — EMP-0042</div>
        <Chip tone="warning">In review</Chip>
      </div>
      <div className="space-y-1.5">
        {items.map((it) => (
          <div
            key={it.k}
            className="flex items-center gap-2.5 rounded-lg bg-white/[0.03] px-2.5 py-2 ring-1 ring-inset ring-white/5"
          >
            <span
              className={cn(
                'flex size-4 items-center justify-center rounded-full ring-1 ring-inset',
                it.done ? 'bg-success/20 text-success ring-success/40' : 'text-muted-foreground ring-white/15',
              )}
            >
              {it.done ? <CheckCircle2 className="size-3.5" /> : null}
            </span>
            <span className={cn('flex-1', it.done ? 'text-foreground' : 'text-muted-foreground')}>{it.k}</span>
            <Chip tone={it.done ? 'success' : 'muted'}>{it.done ? 'Cleared' : 'Pending'}</Chip>
          </div>
        ))}
      </div>
      <div className="flex items-center gap-2 rounded-lg bg-primary/10 px-2.5 py-2 text-[11px] text-primary-bright ring-1 ring-inset ring-primary/25">
        <FileSignature className="size-3.5" /> Relieving &amp; experience letters — preview ready
      </div>
    </div>
  );
}

// ── 6. Security: masked field + audit line ───────────────────────────────────────────────────────────
export function SecurityVignette() {
  const audit = [
    { who: 'HR', act: 'viewed a masked field', tag: 'Audited' },
    { who: 'System', act: 'stored a signed document', tag: 'Record' },
    { who: 'Admin', act: 'updated access', tag: 'Access' },
  ];
  return (
    <div className="space-y-3 text-[12px]">
      <div className="flex items-center justify-between">
        <div className="font-semibold text-foreground">Employee record</div>
        <Chip tone="primary">Access-controlled</Chip>
      </div>
      <div className="grid grid-cols-2 gap-2">
        <div className="rounded-lg bg-white/[0.03] p-2.5 ring-1 ring-inset ring-white/5">
          <div className="text-[10px] uppercase tracking-wide text-muted-foreground">Tax ID</div>
          <div className="mt-1 font-mono text-foreground">•••• •••• ••</div>
        </div>
        <div className="rounded-lg bg-white/[0.03] p-2.5 ring-1 ring-inset ring-white/5">
          <div className="text-[10px] uppercase tracking-wide text-muted-foreground">National ID</div>
          <div className="mt-1 font-mono text-foreground">•••• •••• ••••</div>
        </div>
      </div>
      <div className="rounded-lg bg-black/25 p-2.5 ring-1 ring-inset ring-white/5">
        <div className="mb-1.5 text-[10px] uppercase tracking-wide text-muted-foreground">Audit trail</div>
        <div className="space-y-1">
          {audit.map((a, i) => (
            <div key={i} className="flex items-center gap-2 text-[11px]">
              <span className="size-1.5 rounded-full bg-primary-bright" />
              <span className="text-foreground">{a.who}</span>
              <span className="text-muted-foreground">{a.act}</span>
              <span className="ml-auto">
                <Chip>{a.tag}</Chip>
              </span>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

// ── 7. Requests routing ──────────────────────────────────────────────────────────────────────────────
export function RequestVignette() {
  const rows = [
    { k: 'Payslip', sub: 'July', tone: 'primary' as const, status: 'In progress' },
    { k: 'Experience letter', sub: 'Requested 2 Aug', tone: 'success' as const, status: 'Issued' },
    { k: 'Address proof', sub: 'Requested today', tone: 'warning' as const, status: 'Requested' },
  ];
  return (
    <div className="space-y-2 text-[12px]">
      <div className="flex items-center justify-between">
        <div className="font-semibold text-foreground">My requests</div>
        <Chip tone="primary">Tracked</Chip>
      </div>
      {rows.map((r) => (
        <div
          key={r.k}
          className="flex items-center gap-2.5 rounded-lg bg-white/[0.03] px-2.5 py-2 ring-1 ring-inset ring-white/5"
        >
          <div className="min-w-0 flex-1">
            <div className="truncate font-medium text-foreground">{r.k}</div>
            <div className="text-[11px] text-muted-foreground">{r.sub}</div>
          </div>
          <Chip tone={r.tone}>{r.status}</Chip>
        </div>
      ))}
    </div>
  );
}
