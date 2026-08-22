'use client';

import * as React from 'react';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { useQueryClient } from '@tanstack/react-query';
import {
  Building2,
  CalendarOff,
  CheckCircle2,
  Clock,
  Download,
  FilterX,
  Ban,
  ListChecks,
  Loader2,
  ShieldCheck,
  XCircle,
} from 'lucide-react';
import type { CompanySizeRow, HierarchyCaseRow } from '@/lib/contract';
import { approveOffboarding, getOffboardingCases, rejectOffboarding } from '@/lib/api/offboarding';
import { getHierarchyCompanies } from '@/lib/api/hierarchy';
import { useApiMutation, useApiQuery } from '@/lib/api/hooks';
import { StatTile, type StatTone } from '@/components/dashboard/stat-tile';
import { LoadingSkeleton } from '@/components/loading-skeleton';
import { EmptyState } from '@/components/empty-state';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { surface } from '@/components/ui/surface';
import { cn } from '@/lib/utils';
import { downloadCsv, toCsv } from '@/lib/csv';
import { istTodayIso } from '@/lib/date';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog';

// The whole Offboarding tab shares these two React Query keys. The pending badge in the layout reads the
// SEPARATE /pending query (the global actionable count); a decision here invalidates both so the badge and
// this tab move together.
const CASES_KEY = 'offboarding-cases';
const PENDING_KEY = ['offboarding-pending'] as const;

const SELECT_CLASS =
  'h-11 rounded-md border border-input bg-background px-3 text-sm ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2';

const STATUS_LABEL: Record<string, string> = {
  PENDING_APPROVAL: 'Pending approval',
  APPROVED: 'Approved',
  COMPLETED: 'Completed',
  REJECTED: 'Rejected',
  CANCELLED: 'Cancelled',
};

const STATUS_BADGE: Record<string, React.ComponentProps<typeof Badge>['variant']> = {
  PENDING_APPROVAL: 'warning',
  APPROVED: 'primarySoft',
  COMPLETED: 'success',
  REJECTED: 'danger',
  CANCELLED: 'neutral',
};

// The status filter's options, in a stable order for the dropdown.
const STATUS_OPTIONS = ['PENDING_APPROVAL', 'APPROVED', 'COMPLETED', 'REJECTED', 'CANCELLED'] as const;

/**
 * The HIERARCHY Offboarding tab (§3.6) — everything offboarding in one home: the actionable APPROVALS at the
 * top (the role's only write surface), summary TILES, and the full case HISTORY, all narrowed by the same
 * company / initiated-date / status filters (reflected in the URL so a view is linkable and survives a
 * refresh). Read-only over the minimal-PII contract: the nine inbox fields plus four outcome fields, never
 * any contact detail, document, form, salary or settlement value.
 *
 * One query drives the page (company + date, every status) so the tiles always show real by-status counts;
 * the status filter narrows the History table client-side. The Approvals list respects company + date but
 * ignores the status filter, so a "completed" view never hides work that still needs a decision.
 */
export function OffboardingTab() {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();

  const companyId = searchParams.get('companyId') ?? '';
  const from = searchParams.get('from') ?? '';
  const to = searchParams.get('to') ?? '';
  const status = searchParams.get('status') ?? '';
  const hasFilters = Boolean(companyId || from || to || status);

  const casesQuery = useApiQuery(
    [CASES_KEY, companyId, from, to],
    (s) =>
      getOffboardingCases(
        { companyId: companyId || undefined, from: from || undefined, to: to || undefined },
        s,
      ),
    { refetchOnMount: true, refetchOnWindowFocus: true, placeholderData: (prev) => prev },
  );
  const companiesQuery = useApiQuery(['hierarchy-companies'], (s) => getHierarchyCompanies(s));

  const rows = React.useMemo(() => casesQuery.data ?? [], [casesQuery.data]);
  // Approvals: always the pending cases in the company+date window, regardless of the status filter.
  const pending = React.useMemo(
    () => rows.filter((r) => r.status === 'PENDING_APPROVAL'),
    [rows],
  );
  // History: the same set, narrowed by the status filter when one is chosen. Already newest-first from the API.
  const history = React.useMemo(
    () => (status ? rows.filter((r) => r.status === status) : rows),
    [rows, status],
  );

  const counts = React.useMemo(() => {
    const c = { pending: 0, approved: 0, completed: 0, closed: 0 };
    for (const r of rows) {
      if (r.status === 'PENDING_APPROVAL') c.pending += 1;
      else if (r.status === 'APPROVED') c.approved += 1;
      else if (r.status === 'COMPLETED') c.completed += 1;
      else if (r.status === 'REJECTED' || r.status === 'CANCELLED') c.closed += 1;
    }
    return c;
  }, [rows]);

  function setParam(key: string, value: string) {
    const params = new URLSearchParams(searchParams.toString());
    if (value) params.set(key, value);
    else params.delete(key);
    const qs = params.toString();
    router.replace(qs ? `${pathname}?${qs}` : pathname, { scroll: false });
  }

  function clearFilters() {
    router.replace(pathname, { scroll: false });
  }

  const companyOptions = companiesQuery.data?.companies ?? [];

  return (
    <div className="space-y-6">
      <FiltersBar
        companyId={companyId}
        from={from}
        to={to}
        status={status}
        hasFilters={hasFilters}
        companyOptions={companyOptions}
        onChange={setParam}
        onClear={clearFilters}
      />

      {/* (1) Attention — the actionable approvals, first. */}
      <AttentionSection pending={pending} loading={casesQuery.isLoading} filtered={hasFilters} />

      {/* (2) Summary tiles over the filtered set. */}
      <SummaryTiles counts={counts} loading={casesQuery.isLoading} activeStatus={status} onStatus={(s) => setParam('status', s)} />

      {/* (3) History — every case, newest first. */}
      <HistorySection
        rows={history}
        loading={casesQuery.isLoading}
        hasFilters={hasFilters}
        status={status}
        companyName={companyOptions.find((c) => c.id === companyId)?.name}
        from={from}
        to={to}
      />
    </div>
  );
}

// --- Filters ----------------------------------------------------------------

function FiltersBar({
  companyId,
  from,
  to,
  status,
  hasFilters,
  companyOptions,
  onChange,
  onClear,
}: {
  companyId: string;
  from: string;
  to: string;
  status: string;
  hasFilters: boolean;
  companyOptions: CompanySizeRow[];
  onChange: (key: string, value: string) => void;
  onClear: () => void;
}) {
  return (
    <Card>
      <CardContent className="flex flex-wrap items-end gap-3 p-4">
        <div className="flex min-w-0 flex-col gap-1.5">
          <label htmlFor="ob-company" className="text-xs font-medium text-muted-foreground">
            Company
          </label>
          <select
            id="ob-company"
            value={companyId}
            onChange={(e) => onChange('companyId', e.target.value)}
            className={cn(SELECT_CLASS, 'w-full min-w-0 sm:w-56')}
          >
            <option value="">All companies</option>
            {companyOptions.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
                {c.archived ? ' — archived' : ''}
              </option>
            ))}
          </select>
        </div>
        <div className="flex flex-col gap-1.5">
          <label htmlFor="ob-from" className="text-xs font-medium text-muted-foreground">
            Initiated from
          </label>
          <Input
            id="ob-from"
            type="date"
            value={from}
            max={to || undefined}
            onChange={(e) => onChange('from', e.target.value)}
            className="w-40"
          />
        </div>
        <div className="flex flex-col gap-1.5">
          <label htmlFor="ob-to" className="text-xs font-medium text-muted-foreground">
            Initiated to
          </label>
          <Input
            id="ob-to"
            type="date"
            value={to}
            min={from || undefined}
            onChange={(e) => onChange('to', e.target.value)}
            className="w-40"
          />
        </div>
        <div className="flex flex-col gap-1.5">
          <label htmlFor="ob-status" className="text-xs font-medium text-muted-foreground">
            Status
          </label>
          <select
            id="ob-status"
            value={status}
            onChange={(e) => onChange('status', e.target.value)}
            className={cn(SELECT_CLASS, 'w-full min-w-0 sm:w-48')}
          >
            <option value="">All statuses</option>
            {STATUS_OPTIONS.map((s) => (
              <option key={s} value={s}>
                {STATUS_LABEL[s]}
              </option>
            ))}
          </select>
        </div>
        {hasFilters ? (
          <Button type="button" variant="ghost" size="sm" onClick={onClear}>
            <FilterX />
            Clear filters
          </Button>
        ) : null}
      </CardContent>
    </Card>
  );
}

// --- Summary tiles ----------------------------------------------------------

function SummaryTiles({
  counts,
  loading,
  activeStatus,
  onStatus,
}: {
  counts: { pending: number; approved: number; completed: number; closed: number };
  loading: boolean;
  activeStatus: string;
  onStatus: (status: string) => void;
}) {
  const tiles: { key: string; label: string; value: number; tone: StatTone; icon: React.ComponentType<{ className?: string }> }[] = [
    { key: 'PENDING_APPROVAL', label: 'Pending approval', value: counts.pending, tone: 'warning', icon: Clock },
    { key: 'APPROVED', label: 'Approved · in progress', value: counts.approved, tone: 'primary', icon: Loader2 },
    { key: 'COMPLETED', label: 'Completed', value: counts.completed, tone: 'success', icon: CheckCircle2 },
    { key: 'closed', label: 'Rejected / cancelled', value: counts.closed, tone: 'danger', icon: Ban },
  ];

  if (loading) {
    return (
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        {tiles.map((t) => (
          <Card key={t.key} className="h-24 animate-pulse" />
        ))}
      </div>
    );
  }

  return (
    <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
      {tiles.map((t) => (
        <button
          key={t.key}
          type="button"
          // The "closed" tile spans two statuses, so it does not act as a status filter; the others toggle.
          onClick={t.key === 'closed' ? undefined : () => onStatus(activeStatus === t.key ? '' : t.key)}
          className={cn(
            'rounded-xl text-left ring-offset-background transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2',
            t.key === 'closed' ? 'cursor-default' : 'cursor-pointer',
            activeStatus === t.key ? 'ring-2 ring-ring' : '',
          )}
          aria-pressed={t.key === 'closed' ? undefined : activeStatus === t.key}
        >
          <StatTile icon={t.icon} label={t.label} value={t.value} tone={t.tone} size="sm" />
        </button>
      ))}
    </div>
  );
}

// --- Attention (approvals) --------------------------------------------------

function AttentionSection({
  pending,
  loading,
  filtered,
}: {
  pending: HierarchyCaseRow[];
  loading: boolean;
  filtered: boolean;
}) {
  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-base">
          <ShieldCheck className="size-4 text-muted-foreground" />
          Awaiting your approval
          {pending.length > 0 ? (
            <Badge variant="warning" className="ml-1">
              {pending.length}
            </Badge>
          ) : null}
        </CardTitle>
      </CardHeader>
      <CardContent>
        {loading ? (
          <LoadingSkeleton lines={4} />
        ) : pending.length === 0 ? (
          <EmptyState
            icon={CheckCircle2}
            title="Nothing awaiting approval"
            description={
              filtered
                ? 'No pending offboarding requests match the current company and date filters.'
                : 'Offboarding requests from HR across all companies will appear here.'
            }
          />
        ) : (
          <div className="space-y-3">
            {pending.map((row) => (
              <PendingActionCard key={row.caseId} row={row} />
            ))}
          </div>
        )}
      </CardContent>
    </Card>
  );
}

function PendingActionCard({ row }: { row: HierarchyCaseRow }) {
  return (
    <div className={cn(surface('subtle'), 'flex flex-col gap-4 p-4 sm:flex-row sm:items-start sm:justify-between')}>
      <div className="min-w-0 space-y-2">
        <div className="flex flex-wrap items-center gap-2">
          <span className="font-semibold">{row.employeeName ?? '—'}</span>
          {row.employeeCode ? (
            <span className="font-mono text-xs text-muted-foreground">{row.employeeCode}</span>
          ) : null}
        </div>
        <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-sm text-muted-foreground">
          <span className="flex items-center gap-1.5">
            <Building2 className="size-3.5" />
            {row.companyName ?? '—'}
            {row.teamName ? ` · ${row.teamName}` : ''}
          </span>
          <span className="flex items-center gap-1.5">
            <CalendarOff className="size-3.5" />
            Last day {row.lastWorkingDay ?? '—'}
          </span>
        </div>
        <p className="text-sm">
          <span className="text-muted-foreground">Reason: </span>
          {row.reason ?? '—'}
        </p>
        <p className="text-xs text-muted-foreground">
          Requested by {row.initiatedByName ?? '—'}
          {row.initiatedAt ? ` · ${fmtDate(row.initiatedAt)}` : ''}
        </p>
      </div>
      <div className="flex shrink-0 items-center gap-2">
        <DecisionDialog row={row} mode="approve" />
        <DecisionDialog row={row} mode="reject" />
      </div>
    </div>
  );
}

function DecisionDialog({ row, mode }: { row: HierarchyCaseRow; mode: 'approve' | 'reject' }) {
  const queryClient = useQueryClient();
  const [open, setOpen] = React.useState(false);
  const [note, setNote] = React.useState('');
  const isReject = mode === 'reject';

  const decide = useApiMutation(
    () =>
      isReject
        ? rejectOffboarding(row.caseId, note.trim())
        : approveOffboarding(row.caseId, note.trim() || undefined),
    {
      successMessage: isReject ? 'Offboarding rejected' : 'Offboarding approved',
      onSuccess: () => {
        setOpen(false);
        // Move the history/tiles AND the layout's pending badge together.
        void queryClient.invalidateQueries({ queryKey: [CASES_KEY] });
        void queryClient.invalidateQueries({ queryKey: PENDING_KEY });
      },
    },
  );

  const valid = !isReject || note.trim().length > 0;

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button type="button" variant={isReject ? 'outline' : 'success'} size="sm">
          {isReject ? <XCircle className="size-4" /> : <CheckCircle2 className="size-4" />}
          {isReject ? 'Reject' : 'Approve'}
        </Button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{isReject ? 'Reject offboarding' : 'Approve offboarding'}</DialogTitle>
          <DialogDescription>
            {row.employeeName ?? 'This employee'} · {row.companyName ?? ''}
            {isReject
              ? ' — this is terminal; HR may submit a fresh request later.'
              : ' — HR is notified of the decision.'}
          </DialogDescription>
        </DialogHeader>
        <div className={cn(surface('subtle'), 'space-y-1 p-3 text-sm')}>
          <p>
            <span className="text-muted-foreground">Reason: </span>
            {row.reason ?? '—'}
          </p>
          <p className="flex items-center gap-1.5 text-muted-foreground">
            <CalendarOff className="size-3.5" /> Last working day {row.lastWorkingDay ?? '—'}
          </p>
        </div>
        <div className="space-y-1.5">
          <label className="text-sm font-medium" htmlFor={`note-${row.caseId}-${mode}`}>
            {isReject ? 'Reason for rejection (required)' : 'Note (optional)'}
          </label>
          <Input
            id={`note-${row.caseId}-${mode}`}
            value={note}
            onChange={(e) => setNote(e.target.value)}
          />
        </div>
        <div className="flex justify-end gap-2 pt-2">
          <Button type="button" variant="ghost" onClick={() => setOpen(false)} disabled={decide.isPending}>
            Cancel
          </Button>
          <Button
            type="button"
            variant={isReject ? 'destructive' : 'success'}
            onClick={() => decide.mutate()}
            disabled={!valid || decide.isPending}
          >
            {decide.isPending
              ? isReject
                ? 'Rejecting…'
                : 'Approving…'
              : isReject
                ? 'Reject'
                : 'Approve'}
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

// --- History ----------------------------------------------------------------

const CSV_HEADERS = [
  'Employee',
  'Code',
  'Company',
  'Team',
  'Status',
  'Reason',
  'Initiated',
  'Last working day',
  'Decided by',
  'Decided at',
  'Completed at',
];

function HistorySection({
  rows,
  loading,
  hasFilters,
  status,
  companyName,
  from,
  to,
}: {
  rows: HierarchyCaseRow[];
  loading: boolean;
  hasFilters: boolean;
  status: string;
  companyName?: string;
  from: string;
  to: string;
}) {
  function exportCsv() {
    const body = rows.map((r) => [
      r.employeeName ?? '',
      r.employeeCode ?? '',
      r.companyName ?? '',
      r.teamName ?? '',
      STATUS_LABEL[r.status ?? ''] ?? r.status ?? '',
      r.reason ?? '',
      r.initiatedAt ? fmtDate(r.initiatedAt) : '',
      r.lastWorkingDay ?? '',
      r.decidedByName ?? '',
      r.decidedAt ? fmtDate(r.decidedAt) : '',
      r.completedAt ? fmtDate(r.completedAt) : '',
    ]);
    downloadCsv(`offboarding-history-${istTodayIso()}.csv`, toCsv(CSV_HEADERS, body));
  }

  return (
    <Card>
      <CardHeader className="flex flex-row flex-wrap items-center justify-between gap-2">
        <CardTitle className="flex items-center gap-2 text-base">
          <ListChecks className="size-4 text-muted-foreground" />
          History
          <span className="text-sm font-normal text-muted-foreground">({rows.length})</span>
        </CardTitle>
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={exportCsv}
          disabled={rows.length === 0}
        >
          <Download className="size-4" />
          Export CSV
        </Button>
      </CardHeader>
      <CardContent>
        {loading ? (
          <LoadingSkeleton lines={6} />
        ) : rows.length === 0 ? (
          <EmptyState
            icon={ListChecks}
            title={hasFilters ? 'No cases match these filters' : 'No offboarding cases yet'}
            description={
              hasFilters
                ? `No cases${activeFilterPhrase(status, companyName, from, to)}. Adjust or clear the filters to see more.`
                : 'Offboarding cases appear here as HR initiates them across every company.'
            }
          />
        ) : (
          <>
            {/* Desktop: a scannable table (scrolls inside its own box, never the page). */}
            <div className="hidden overflow-x-auto sm:block">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b text-left text-xs text-muted-foreground">
                    <th className="py-2 pr-3 font-medium">Employee</th>
                    <th className="py-2 pr-3 font-medium">Company / team</th>
                    <th className="py-2 pr-3 font-medium">Status</th>
                    <th className="py-2 pr-3 font-medium">Initiated</th>
                    <th className="py-2 pr-3 font-medium">Last day</th>
                    <th className="py-2 pr-3 font-medium">Decided</th>
                    <th className="py-2 font-medium">Completed</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((r) => (
                    <tr key={r.caseId} className="border-b last:border-0 align-top">
                      <td className="py-2.5 pr-3">
                        <div className="font-medium">{r.employeeName ?? '—'}</div>
                        {r.employeeCode ? (
                          <div className="font-mono text-xs text-muted-foreground">{r.employeeCode}</div>
                        ) : null}
                      </td>
                      <td className="py-2.5 pr-3">
                        <div>{r.companyName ?? '—'}</div>
                        {r.teamName ? (
                          <div className="text-xs text-muted-foreground">{r.teamName}</div>
                        ) : null}
                      </td>
                      <td className="py-2.5 pr-3">
                        <Badge variant={STATUS_BADGE[r.status ?? ''] ?? 'neutral'}>
                          {STATUS_LABEL[r.status ?? ''] ?? r.status ?? '—'}
                        </Badge>
                      </td>
                      <td className="py-2.5 pr-3 whitespace-nowrap text-muted-foreground">
                        {fmtDate(r.initiatedAt)}
                      </td>
                      <td className="py-2.5 pr-3 whitespace-nowrap text-muted-foreground">
                        {r.lastWorkingDay ?? '—'}
                      </td>
                      <td className="py-2.5 pr-3 whitespace-nowrap text-muted-foreground">
                        {r.decidedAt ? (
                          <>
                            {fmtDate(r.decidedAt)}
                            {r.decidedByName ? (
                              <div className="text-xs">by {r.decidedByName}</div>
                            ) : null}
                          </>
                        ) : (
                          '—'
                        )}
                      </td>
                      <td className="py-2.5 whitespace-nowrap text-muted-foreground">
                        {fmtDate(r.completedAt)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {/* Mobile: the same rows, stacked as cards — no horizontal scroll. */}
            <div className="space-y-3 sm:hidden">
              {rows.map((r) => (
                <div key={r.caseId} className={cn(surface('subtle'), 'space-y-2 p-3')}>
                  <div className="flex items-start justify-between gap-2">
                    <div className="min-w-0">
                      <div className="font-medium">{r.employeeName ?? '—'}</div>
                      {r.employeeCode ? (
                        <div className="font-mono text-xs text-muted-foreground">{r.employeeCode}</div>
                      ) : null}
                    </div>
                    <Badge variant={STATUS_BADGE[r.status ?? ''] ?? 'neutral'} className="shrink-0">
                      {STATUS_LABEL[r.status ?? ''] ?? r.status ?? '—'}
                    </Badge>
                  </div>
                  <div className="text-sm text-muted-foreground">
                    {r.companyName ?? '—'}
                    {r.teamName ? ` · ${r.teamName}` : ''}
                  </div>
                  <dl className="grid grid-cols-2 gap-x-3 gap-y-1 text-xs text-muted-foreground">
                    <Meta label="Initiated" value={fmtDate(r.initiatedAt)} />
                    <Meta label="Last day" value={r.lastWorkingDay ?? '—'} />
                    <Meta
                      label="Decided"
                      value={r.decidedAt ? `${fmtDate(r.decidedAt)}${r.decidedByName ? ` · ${r.decidedByName}` : ''}` : '—'}
                    />
                    <Meta label="Completed" value={fmtDate(r.completedAt)} />
                  </dl>
                </div>
              ))}
            </div>
          </>
        )}
      </CardContent>
    </Card>
  );
}

function Meta({ label, value }: { label: string; value: string }) {
  return (
    <div className="min-w-0">
      <dt className="text-[11px] uppercase tracking-wide">{label}</dt>
      <dd className="truncate text-foreground/80">{value}</dd>
    </div>
  );
}

// --- helpers ----------------------------------------------------------------

/** A local date label, or an em dash for a null/absent timestamp. */
function fmtDate(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? '—' : d.toLocaleDateString();
}

/** Names the active filters for the empty state, e.g. " with status Approved at Globex from 2026-01-01". */
function activeFilterPhrase(status: string, companyName: string | undefined, from: string, to: string): string {
  const parts: string[] = [];
  if (status) parts.push(`with status ${STATUS_LABEL[status] ?? status}`);
  if (companyName) parts.push(`at ${companyName}`);
  if (from) parts.push(`initiated on or after ${from}`);
  if (to) parts.push(`initiated on or before ${to}`);
  return parts.length ? ` ${parts.join(', ')}` : '';
}
