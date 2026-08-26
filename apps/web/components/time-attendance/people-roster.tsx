'use client';

import * as React from 'react';
import Link from 'next/link';
import type { ColumnDef } from '@tanstack/react-table';
import { useQueryClient } from '@tanstack/react-query';
import { EyeOff, Link2, Upload, UserRound, Users } from 'lucide-react';
import {
  editPerson,
  iclockKeys,
  listPeople,
  type IclockPerson,
} from '@/lib/api/iclock';
import { useApiMutation, useApiQuery } from '@/lib/api/hooks';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { DataTable } from '@/components/data-table';
import { EmptyState } from '@/components/empty-state';
import { TableSkeleton } from '@/components/loading-skeleton';
import { cn } from '@/lib/utils';
import { RosterImportDialog } from './roster-import-dialog';
import { LinkSuggestionsDialog } from './link-suggestions';
import { UnmappedPinInbox } from './unmapped-pin-inbox';
import { ConsoleError } from './console-error';

const SELECT_CLASS =
  'h-11 rounded-md border border-input bg-background px-3 text-sm ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2';

type Filter = 'all' | 'active' | 'inactive' | 'excluded' | 'unlinked' | 'unnamed';

const FILTERS: { value: Filter; label: string }[] = [
  { value: 'all', label: 'Everyone' },
  { value: 'active', label: 'Active' },
  { value: 'inactive', label: 'Inactive' },
  { value: 'excluded', label: 'Excluded from reports' },
  { value: 'unlinked', label: 'Not linked to IHRMS' },
  { value: 'unnamed', label: 'Unnamed' },
];

/**
 * "Excluded from reports" — a badge and a toggle, deliberately worded as reporting, not departure.
 *
 * The legacy tool's "deleted" flag meant report-exclusion, and reading it as departure would erase
 * current employees from the floor. So this flag is orthogonal to `active`: an excluded person still
 * resolves, still promotes, still appears on the live board and the day view. The tooltip says so,
 * because a badge that reads like a soft delete is exactly how someone gets switched off by mistake.
 */
function ExclusionToggle({ person }: { person: IclockPerson }) {
  const qc = useQueryClient();
  const toggle = useApiMutation(
    () =>
      editPerson(person.id, {
        pin: person.pin,
        excludedFromReports: !person.excludedFromReports,
      }),
    {
      successMessage: (p) =>
        p.excludedFromReports
          ? 'Excluded from reports. They still appear on the board.'
          : 'Included in reports again.',
      onSuccess: () => qc.invalidateQueries({ queryKey: iclockKeys.people(person.siteId) }),
    },
  );

  return (
    <button
      type="button"
      onClick={() => toggle.mutate()}
      disabled={toggle.isPending}
      title={
        person.excludedFromReports
          ? 'Excluded from reports and warning mail — but still tracked on the board and day view. Click to include again.'
          : 'Click to exclude from reports and warning mail. Attendance tracking is unaffected.'
      }
      className={cn(
        'rounded-full transition-opacity focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2',
        toggle.isPending && 'opacity-50',
      )}
    >
      {person.excludedFromReports ? (
        <Badge variant="neutral">
          <EyeOff className="size-3" />
          Not in reports
        </Badge>
      ) : (
        <Badge variant="outline" className="opacity-40 hover:opacity-100">
          In reports
        </Badge>
      )}
    </button>
  );
}

export function PeopleRoster({ siteId }: { siteId: string }) {
  const [filter, setFilter] = React.useState<Filter>('all');
  const [company, setCompany] = React.useState('');
  const [importOpen, setImportOpen] = React.useState(false);
  const [linking, setLinking] = React.useState<IclockPerson | null>(null);

  const query = useApiQuery(
    iclockKeys.people(siteId),
    (signal) => listPeople(siteId, signal),
    { retry: false },
  );

  const people = React.useMemo(() => query.data ?? [], [query.data]);

  const companies = React.useMemo(() => {
    const seen = new Map<string, string>();
    for (const p of people) {
      const label = p.companyName ?? p.companyLabel;
      if (label) seen.set(label, label);
    }
    return [...seen.keys()].sort();
  }, [people]);

  const rows = React.useMemo(
    () =>
      people.filter((p) => {
        if (company && (p.companyName ?? p.companyLabel) !== company) return false;
        switch (filter) {
          case 'active':
            return p.active;
          case 'inactive':
            return !p.active;
          case 'excluded':
            return p.excludedFromReports;
          case 'unlinked':
            return !p.employeeId;
          case 'unnamed':
            return p.unnamed;
          default:
            return true;
        }
      }),
    [people, filter, company],
  );

  const columns = React.useMemo<ColumnDef<IclockPerson>[]>(
    () => [
      {
        id: 'person',
        accessorFn: (p) => `${p.name ?? ''} ${p.pin} ${p.email ?? ''}`,
        header: 'Person',
        cell: ({ row }) => {
          const p = row.original;
          return (
            <Link
              href={`/super-admin/time-attendance/people/${p.id}?site=${siteId}`}
              className="block min-w-0"
            >
              <div className="truncate font-medium">
                {p.name ?? <span className="text-muted-foreground">Unnamed</span>}
              </div>
              <div className="truncate font-mono text-xs text-muted-foreground">
                {p.pin}
                {p.email ? ` · ${p.email}` : ''}
              </div>
            </Link>
          );
        },
      },
      {
        id: 'company',
        accessorFn: (p) => p.companyName ?? p.companyLabel ?? '',
        header: 'Company',
        cell: ({ row }) => {
          const p = row.original;
          if (p.companyName) {
            return (
              <span className="text-sm">
                {p.companyName}
                {p.team ? <span className="text-muted-foreground"> · {p.team}</span> : null}
              </span>
            );
          }
          if (p.companyLabel) {
            // The label came from the import but matched no IHRMS company. Kept and shown, because
            // "a company exists on the terminals but not in IHRMS" is information, not noise.
            return (
              <span className="text-sm text-muted-foreground" title="No matching company in IHRMS">
                {p.companyLabel}
              </span>
            );
          }
          return <span className="text-sm text-muted-foreground">—</span>;
        },
      },
      {
        id: 'ihrms',
        accessorFn: (p) => p.employeeName ?? '',
        header: 'IHRMS employee',
        cell: ({ row }) => {
          const p = row.original;
          return p.employeeId ? (
            <span className="truncate text-sm">{p.employeeName ?? p.employeeId}</span>
          ) : (
            <Button
              variant="ghost"
              size="sm"
              className="h-8 px-2 text-muted-foreground"
              onClick={() => setLinking(p)}
            >
              <Link2 className="size-3.5" />
              Link
            </Button>
          );
        },
      },
      {
        accessorKey: 'punchCount',
        header: 'Punches',
        cell: ({ row }) => (
          <span className="tabular-nums text-sm text-muted-foreground">
            {row.original.punchCount}
          </span>
        ),
      },
      {
        id: 'flags',
        header: 'Status',
        enableSorting: false,
        cell: ({ row }) => {
          const p = row.original;
          return (
            <div className="flex flex-wrap items-center gap-1.5">
              {!p.active ? <Badge variant="warning">Inactive</Badge> : null}
              {p.duplicateEmail ? (
                <Badge variant="danger" title="Another person at this site has the same email.">
                  Shared email
                </Badge>
              ) : null}
              <ExclusionToggle person={p} />
            </div>
          );
        },
      },
    ],
    [siteId],
  );

  if (query.isLoading) return <TableSkeleton rows={8} cols={5} />;
  if (query.isError || !query.data) return <ConsoleError error={query.error} />;

  // EMPTY STATE — the roster has never been imported. The first screen an operator meets on day one.
  if (people.length === 0) {
    return (
      <>
        <EmptyState
          icon={Users}
          title="The roster is empty"
          description="Import the people enrolled on the terminals. Every punch already captured resolves retroactively once they exist — nothing that has been recorded is lost."
          action={
            <Button onClick={() => setImportOpen(true)}>
              <Upload className="size-4" />
              Import roster
            </Button>
          }
        />
        <RosterImportDialog siteId={siteId} open={importOpen} onOpenChange={setImportOpen} />
      </>
    );
  }

  return (
    <div className="space-y-6">
      <DataTable
        columns={columns}
        data={rows}
        carded
        searchPlaceholder="Search by name, pin or email…"
        toolbar={
          <>
            <select
              className={SELECT_CLASS}
              value={filter}
              onChange={(e) => setFilter(e.target.value as Filter)}
              aria-label="Filter people"
            >
              {FILTERS.map((f) => (
                <option key={f.value} value={f.value}>
                  {f.label}
                </option>
              ))}
            </select>
            {companies.length > 1 ? (
              <select
                className={SELECT_CLASS}
                value={company}
                onChange={(e) => setCompany(e.target.value)}
                aria-label="Filter by company"
              >
                <option value="">All companies</option>
                {companies.map((c) => (
                  <option key={c} value={c}>
                    {c}
                  </option>
                ))}
              </select>
            ) : null}
            {/* The roster total, not a filtered count. DataTable owns its own search box and applies
                it AFTER these selects, so any "N of M" computed here is a number the table is not
                showing — it would read as a filter result while ignoring whatever was typed. */}
            <span className="text-sm tabular-nums text-muted-foreground">
              {people.length} on the roster
            </span>
            <Button variant="outline" size="sm" className="ml-auto" onClick={() => setImportOpen(true)}>
              <Upload className="size-4" />
              Import
            </Button>
          </>
        }
        emptyState={
          <EmptyState
            icon={UserRound}
            title="Nobody matches that filter"
            description="Try a different filter, or clear the search."
            className="border-0 py-10"
          />
        }
      />

      <UnmappedPinInbox siteId={siteId} />

      <RosterImportDialog siteId={siteId} open={importOpen} onOpenChange={setImportOpen} />
      <LinkSuggestionsDialog
        person={linking}
        open={Boolean(linking)}
        onOpenChange={(open) => {
          if (!open) setLinking(null);
        }}
      />
    </div>
  );
}
