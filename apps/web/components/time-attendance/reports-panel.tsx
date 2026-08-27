'use client';

import * as React from 'react';
import Link from 'next/link';
import { BarChart3, Download, MailWarning, TriangleAlert } from 'lucide-react';
import {
  getMonthlyReport,
  getWarningPreview,
  iclockKeys,
  payrollCsvUrl,
  type MonthlyReport,
  type PersonReport,
  type WarningLetter,
} from '@/lib/api/iclock';
import { useApiQuery } from '@/lib/api/hooks';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import { EmptyState } from '@/components/empty-state';
import { LoadingSkeleton } from '@/components/loading-skeleton';
import { GroupedList } from '@/components/console/grouped-list';
import { ViewToggle, usePersistedViewMode } from '@/components/console/view-toggle';
import { cn } from '@/lib/utils';
import { ConsoleError } from './console-error';

/** {@code 505} to {@code 8:25}. Mirrors the server's formatter so the screen and the CSV agree. */
function hm(minutes: number): string {
  const m = Math.max(0, minutes);
  return `${Math.floor(m / 60)}:${String(m % 60).padStart(2, '0')}`;
}

/** The current month in IST, as the default period. */
function currentPeriod(): string {
  return new Date()
    .toLocaleDateString('en-CA', { timeZone: 'Asia/Kolkata' })
    .slice(0, 7);
}

function periodLabel(period: string): string {
  const [y, m] = period.split('-').map(Number);
  if (!y || !m) return period;
  return new Date(Date.UTC(y, m - 1, 1)).toLocaleDateString('en-US', {
    month: 'long',
    year: 'numeric',
    timeZone: 'UTC',
  });
}

/**
 * The month, per person.
 *
 * Sorted worst-first by the SERVER — people carrying LOP at the top. An operator opening this with ten
 * minutes to spare should find the conversations they need to have, not whoever sorts first
 * alphabetically.
 */
function PersonRow({ r }: { r: PersonReport }) {
  return (
    <div className="flex flex-wrap items-center gap-x-4 gap-y-1 px-3 py-2 text-sm">
      <span className="min-w-0 flex-1">
        <span className="font-medium">{r.name ?? 'Unnamed'}</span>{' '}
        <span className="text-xs text-muted-foreground">{r.pin}</span>
        {r.team ? <span className="ml-2 text-xs text-muted-foreground">{r.team}</span> : null}
      </span>

      <span className="tabular-nums text-muted-foreground">
        {r.presentDays}/{r.workingDays} days
      </span>
      <span className="tabular-nums text-muted-foreground">{hm(r.workedMin)} worked</span>

      {r.lateDays > 0 ? (
        <span className="tabular-nums text-warning">
          {r.lateDays} late · {hm(r.lateMin)}
        </span>
      ) : (
        <span className="text-xs text-muted-foreground">on time</span>
      )}

      {r.excessBreakMin > 0 ? (
        <span className="tabular-nums text-warning">+{hm(r.excessBreakMin)} break</span>
      ) : null}

      {r.daysWithMissingPunch > 0 ? (
        <Badge variant="outline" className="gap-1 text-xs">
          <TriangleAlert className="size-3" aria-hidden />
          {r.daysWithMissingPunch} incomplete
        </Badge>
      ) : null}

      {r.lopDays > 0 ? (
        <Badge variant="danger" className="tabular-nums">
          {r.lopDays} LOP
        </Badge>
      ) : null}
    </div>
  );
}

export function ReportsPanel({ siteId }: { siteId: string }) {
  const [period, setPeriod] = React.useState(currentPeriod());
  const [axis, setAxis] = React.useState<'companyName' | 'team' | 'shiftProfile'>('companyName');
  const [mode, setMode] = usePersistedViewMode('reports');
  const [showWarnings, setShowWarnings] = React.useState(false);

  const query = useApiQuery<MonthlyReport>(
    iclockKeys.report(siteId, period),
    (signal) => getMonthlyReport(siteId, period, signal),
    { retry: false },
  );

  if (query.isLoading) return <LoadingSkeleton lines={10} />;
  if (query.isError || !query.data) return <ConsoleError error={query.error} />;

  const report = query.data;
  const withLop = report.rows.filter((r) => r.lopDays > 0);
  const totalLop = withLop.reduce((a, r) => a + r.lopDays, 0);

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div className="space-y-1">
          <label className="text-xs font-medium text-muted-foreground" htmlFor="period">
            Period
          </label>
          <Input
            id="period"
            type="month"
            value={period}
            onChange={(e) => setPeriod(e.target.value)}
            className="w-44"
          />
          <p className="text-xs text-muted-foreground">
            Shift days {report.period.from} to {report.period.to}
          </p>
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <select
            aria-label="Group by"
            value={axis}
            onChange={(e) => setAxis(e.target.value as typeof axis)}
            className="h-9 rounded-md border border-border bg-card px-2 text-sm"
          >
            <option value="companyName">Company</option>
            <option value="team">Team</option>
            <option value="shiftProfile">Shift</option>
          </select>
          <ViewToggle value={mode} onChange={setMode} />
          <Button asChild size="sm" variant="outline">
            <a href={payrollCsvUrl(siteId, period)} rel="noreferrer">
              <Download className="mr-1.5 size-4" aria-hidden />
              Payroll CSV
            </a>
          </Button>
          <Button
            size="sm"
            variant={showWarnings ? 'default' : 'outline'}
            onClick={() => setShowWarnings((s) => !s)}
          >
            <MailWarning className="mr-1.5 size-4" aria-hidden />
            Warnings ({withLop.length})
          </Button>
        </div>
      </div>

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <Stat label="Reported" value={String(report.peopleReported)}
          hint={report.peopleExcluded > 0 ? `${report.peopleExcluded} excluded` : undefined} />
        <Stat label="Carrying LOP" value={String(withLop.length)} hint={`${totalLop} day(s) total`} />
        <Stat label="Late days" value={String(report.rows.reduce((a, r) => a + r.lateDays, 0))} />
        <Stat
          label="Incomplete days"
          value={String(report.rows.reduce((a, r) => a + r.daysWithMissingPunch, 0))}
          hint="a session never closed"
        />
      </div>

      {showWarnings ? <WarningList siteId={siteId} period={period} /> : null}

      {report.rows.length === 0 ? (
        <EmptyState
          icon={BarChart3}
          title="Nothing to report"
          description="No active, reportable people at this building for the period."
        />
      ) : (
        <GroupedList
          items={report.rows}
          grouping={{
            mode,
            keyOf: (r) => r[axis],
            ungroupedLabel: 'Unassigned',
            // Worst-first, matching the server's own ordering. A report is read for the people who
            // need a conversation, so recency — the default everywhere else — would be wrong here.
            compare: (a, b) =>
              b.lopDays - a.lopDays ||
              b.lateDays - a.lateDays ||
              (a.name ?? '￿').localeCompare(b.name ?? '￿'),
          }}
          renderItem={(r) => <PersonRow r={r} />}
          itemKey={(r) => r.personId}
          badgeVariant="warning"
          storageKey="reports"
        />
      )}
    </div>
  );
}

function Stat({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <div className="rounded-xl border border-border bg-card px-4 py-3">
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className="mt-0.5 text-2xl font-semibold tabular-nums">{value}</div>
      {hint ? <div className="text-xs text-muted-foreground">{hint}</div> : null}
    </div>
  );
}

/**
 * The letters this period would produce.
 *
 * A PREVIEW, and deliberately the only mail surface for now. Nobody should be able to send to two
 * hundred people before somebody has read one of the letters.
 */
function WarningList({ siteId, period }: { siteId: string; period: string }) {
  const query = useApiQuery<WarningLetter[]>(
    iclockKeys.warnings(siteId, period),
    (signal) => getWarningPreview(siteId, period, signal),
    { retry: false },
  );

  if (query.isLoading) return <LoadingSkeleton lines={4} />;
  if (query.isError || !query.data) return <ConsoleError error={query.error} />;

  const letters = query.data;

  return (
    <div className="rounded-xl border border-warning/25 bg-warning/5 p-4">
      <div className="mb-2 flex items-center gap-2 text-sm font-semibold text-warning">
        <MailWarning className="size-4" aria-hidden />
        {letters.length} warning letter{letters.length === 1 ? '' : 's'} for {periodLabel(period)}
      </div>
      <p className="mb-3 text-xs text-muted-foreground">
        Composed, not sent. Sending stays operator-triggered and is switched off until the mail
        accounts are configured. Only people past the permitted three late logins appear here.
      </p>
      {letters.length === 0 ? (
        <p className="text-sm text-muted-foreground">
          Nobody exceeded the permitted three late logins this period.
        </p>
      ) : (
        <ul className="space-y-2">
          {letters.map((l) => (
            <li key={l.personId} className="rounded-lg border border-border bg-card">
              <details>
                <summary className="cursor-pointer px-3 py-2 text-sm">
                  <span className="font-medium">{l.name ?? 'Unnamed'}</span>{' '}
                  <span className="text-xs text-muted-foreground">{l.pin}</span>
                  <span className="ml-2 text-xs text-muted-foreground">{l.company}</span>
                  <Badge variant="danger" className="ml-2 tabular-nums">
                    {l.lopDays} LOP
                  </Badge>
                </summary>
                <div className="border-t border-border px-3 py-2">
                  <div className="text-xs font-medium text-muted-foreground">{l.subject}</div>
                  <pre className={cn(
                    'mt-2 max-h-72 overflow-auto whitespace-pre-wrap',
                    'font-sans text-xs leading-relaxed text-foreground',
                  )}>{l.body}</pre>
                </div>
              </details>
            </li>
          ))}
        </ul>
      )}
      <p className="mt-3 text-xs text-muted-foreground">
        <Link href="#" className="underline underline-offset-2">Sending</Link> arrives once the Zoho
        accounts are configured; the numbers above come from this report and are never recomputed.
      </p>
    </div>
  );
}
