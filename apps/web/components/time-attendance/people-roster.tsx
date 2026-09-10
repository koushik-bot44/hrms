'use client';

import * as React from 'react';
import Link from 'next/link';
import { useQueryClient } from '@tanstack/react-query';
import { ArrowDownUp, CalendarClock, Eye, EyeOff, Link2, Link2Off, Pencil, Search, Trash2, Upload, UserPlus, UserRound, Users } from 'lucide-react';
import { editPerson, iclockKeys, listPeople, type IclockPerson } from '@/lib/api/iclock';
import { useApiMutation, useApiQuery } from '@/lib/api/hooks';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Input } from '@/components/ui/input';
import { EmptyState } from '@/components/empty-state';
import { TableSkeleton } from '@/components/loading-skeleton';
import { GroupedList } from '@/components/console/grouped-list';
import { Combobox } from '@/components/console/combobox';
import { RowMenu } from '@/components/console/row-menu';
import type { GroupOptions } from '@/lib/console/grouping';
import { RosterImportDialog } from './roster-import-dialog';
import { LinkSuggestionsDialog } from './link-suggestions';
import { DeletePersonDialog } from './delete-person-dialog';
import { PersonEditDialog } from './person-edit-dialog';
import { BulkShiftDialog, InlineShiftPicker, ShiftBadge } from './shift-controls';
import { CommandChannelBadge } from './device-commands';
import { UnmappedPinInbox } from './unmapped-pin-inbox';
import { ConsoleError } from './console-error';

type Filter = 'all' | 'active' | 'inactive' | 'excluded' | 'unlinked' | 'unnamed';
type Axis = 'company' | 'team' | 'status';
type SortBy = 'name' | 'pin' | 'punches';

const FILTERS: { value: Filter; label: string }[] = [
  { value: 'all', label: 'Everyone' },
  { value: 'active', label: 'Active' },
  { value: 'inactive', label: 'Inactive' },
  { value: 'excluded', label: 'Excluded from reports' },
  { value: 'unlinked', label: 'Not linked to IHRMS' },
  { value: 'unnamed', label: 'Unnamed' },
];

const AXES: { value: Axis; label: string }[] = [
  { value: 'company', label: 'Group by company' },
  { value: 'team', label: 'Group by team' },
  { value: 'status', label: 'Group by status' },
];

const SORTS: { value: SortBy; label: string }[] = [
  { value: 'name', label: 'Sort by name' },
  { value: 'pin', label: 'Sort by pin' },
  { value: 'punches', label: 'Sort by punches' },
];

const collator = new Intl.Collator(undefined, { sensitivity: 'base' });

/** Unnamed people sink: they are a cleanup task, not the headline. */
function byName(a: IclockPerson, b: IclockPerson): number {
  if (!a.name && !b.name) return collator.compare(a.pin, b.pin);
  if (!a.name) return 1;
  if (!b.name) return -1;
  return collator.compare(a.name, b.name) || collator.compare(a.pin, b.pin);
}

/**
 * The screen's grouping and ordering, expressed ENTIRELY as parameters to the shared rule.
 *
 * The People directory is exempt from newest-first — a directory is looked up, not watched — so it
 * passes an explicit comparator rather than the renderer re-sorting anything. Sortable columns work the
 * same way: the sort choice selects a comparator that goes INTO the function. No ordering or grouping
 * logic lives in the row renderer, by design; that is the boundary the shared rule exists to hold.
 */
function groupingFor(axis: Axis, sort: SortBy): GroupOptions<IclockPerson> {
  const compare =
    sort === 'pin'
      ? (a: IclockPerson, b: IclockPerson) => collator.compare(a.pin, b.pin)
      : sort === 'punches'
        ? (a: IclockPerson, b: IclockPerson) => b.punchCount - a.punchCount || byName(a, b)
        : byName;

  if (axis === 'team') {
    return { keyOf: (p) => p.team, ungroupedLabel: 'No team', compare };
  }
  if (axis === 'status') {
    return {
      keyOf: (p) =>
        !p.active ? 'Inactive' : p.excludedFromReports ? 'Excluded from reports' : 'Active',
      ungroupedLabel: 'Unknown',
      compare,
    };
  }
  return {
    keyOf: (p) => p.companyName ?? p.companyLabel,
    ungroupedLabel: 'No company',
    compare,
  };
}

/**
 * "Excluded from reports" — worded as reporting, never departure.
 *
 * Orthogonal to `active`: an excluded person still resolves, still promotes, still appears on the live
 * board and the day view. The legacy tool's "deleted" meant report-exclusion, and reading it as
 * departure is exactly how two current employees nearly vanished from the floor.
 */
function ExclusionBadge({ person }: { person: IclockPerson }) {
  if (!person.excludedFromReports) return null;
  return (
    <Badge
      variant="neutral"
      title="Excluded from reports and warning mail — still tracked on the board and day view."
    >
      <EyeOff className="size-3" />
      Not in reports
    </Badge>
  );
}

function PersonRow({
  person,
  siteId,
  onLink,
  onEdit,
  onDelete,
  selected,
  onSelect,
}: {
  person: IclockPerson;
  siteId: string;
  onLink: (p: IclockPerson) => void;
  onEdit: (p: IclockPerson) => void;
  onDelete: (p: IclockPerson) => void;
  selected: boolean;
  onSelect: (id: string, checked: boolean) => void;
}) {
  const qc = useQueryClient();
  const toggleExclusion = useApiMutation(
    () =>
      editPerson(person.id, { pin: person.pin, excludedFromReports: !person.excludedFromReports }),
    {
      successMessage: (p) =>
        p.excludedFromReports
          ? 'Excluded from reports. They still appear on the board.'
          : 'Included in reports again.',
      onSuccess: () => qc.invalidateQueries({ queryKey: iclockKeys.people(person.siteId) }),
    },
  );
  const toggleActive = useApiMutation(
    () => editPerson(person.id, { pin: person.pin, active: !person.active }),
    {
      successMessage: (p) => (p.active ? 'Reactivated.' : 'Deactivated — their punches stop resolving.'),
      onSuccess: () => qc.invalidateQueries({ queryKey: iclockKeys.people(person.siteId) }),
    },
  );

  return (
    <div className="flex items-center gap-3 px-3 py-2 transition-colors hover:bg-accent">
      {/* Outside the Link, deliberately: a checkbox nested in an anchor navigates instead of
          selecting on roughly half of clicks, which is the sort of bug that reads as flakiness. */}
      <input
        type="checkbox"
        checked={selected}
        onChange={(e) => onSelect(person.id, e.target.checked)}
        aria-label={`Select ${person.name ?? person.pin}`}
        className="size-4 shrink-0 cursor-pointer accent-primary"
      />
      <Link
        href={`/super-admin/time-attendance/people/${person.id}?site=${siteId}`}
        className="flex min-w-0 flex-1 items-center gap-3"
      >
        <span className="min-w-0 flex-1 truncate text-sm">
          {person.name ?? <span className="text-muted-foreground">Unnamed</span>}
          <span className="ml-2 font-mono text-xs text-muted-foreground">{person.pin}</span>
        </span>
        <span className="hidden min-w-0 flex-1 truncate text-xs text-muted-foreground sm:block">
          {person.employeeId ? (person.employeeName ?? 'Linked') : '—'}
        </span>
        <span className="shrink-0 tabular-nums text-xs text-muted-foreground">
          {person.punchCount}
        </span>
      </Link>
      <div className="flex shrink-0 items-center gap-1.5">
        {!person.active ? <Badge variant="warning">Inactive</Badge> : null}
        {person.duplicateEmail ? (
          <Badge variant="danger" title="Another person at this site has the same email.">
            Shared email
          </Badge>
        ) : null}
        <ExclusionBadge person={person} />
        {/* The badge reads at a glance; the picker is the change. Both, because a roster with two
            shifts on it has to answer "which shift is this row?" before it answers anything else. */}
        <ShiftBadge profile={person.shiftProfile} />
        <InlineShiftPicker siteId={siteId} person={person} />
        <RowMenu
          label={`Actions for ${person.name ?? person.pin}`}
          actions={[
            {
              label: 'Edit…',
              icon: Pencil,
              onSelect: () => onEdit(person),
            },
            {
              label: person.employeeId ? 'Change IHRMS link' : 'Link to IHRMS employee',
              icon: person.employeeId ? Link2Off : Link2,
              onSelect: () => onLink(person),
            },
            {
              label: person.excludedFromReports ? 'Include in reports' : 'Exclude from reports',
              icon: person.excludedFromReports ? Eye : EyeOff,
              onSelect: () => toggleExclusion.mutate(),
              disabled: toggleExclusion.isPending,
            },
            {
              label: person.active ? 'Deactivate' : 'Reactivate',
              onSelect: () => toggleActive.mutate(),
              disabled: toggleActive.isPending,
              danger: person.active,
            },
            {
              label: 'Delete from roster…',
              icon: Trash2,
              onSelect: () => onDelete(person),
              danger: true,
            },
          ]}
        />
      </div>
    </div>
  );
}

export function PeopleRoster({ siteId }: { siteId: string }) {
  // Default to ACTIVE: the roster's working view is who currently resolves at the gate. Inactive and
  // excluded people stay one dropdown away rather than padding the default list.
  const [filter, setFilter] = React.useState<Filter>('active');
  const [company, setCompany] = React.useState('');
  const [axis, setAxis] = React.useState<Axis>('company');
  const [sort, setSort] = React.useState<SortBy>('name');
  const [search, setSearch] = React.useState('');
  const [importOpen, setImportOpen] = React.useState(false);
  const [addingPerson, setAddingPerson] = React.useState(false);
  const [linking, setLinking] = React.useState<IclockPerson | null>(null);
  const [editing, setEditing] = React.useState<IclockPerson | null>(null);
  const [picked, setPicked] = React.useState<Set<string>>(new Set());
  const [bulkOpen, setBulkOpen] = React.useState(false);
  const [deleting, setDeleting] = React.useState<IclockPerson | null>(null);

  const query = useApiQuery(iclockKeys.people(siteId), (signal) => listPeople(siteId, signal), {
    retry: false,
  });
  const people = React.useMemo(() => query.data ?? [], [query.data]);

  const companies = React.useMemo(() => {
    const seen = new Set<string>();
    for (const p of people) {
      const label = p.companyName ?? p.companyLabel;
      if (label) seen.add(label);
    }
    return [...seen].sort((a, b) => collator.compare(a, b));
  }, [people]);

  // FILTERING only — which items to show. Grouping, ordering and collapse thresholds all live in the
  // shared rule; nothing here decides layout.
  const rows = React.useMemo(() => {
    const needle = search.trim().toLowerCase();
    return people.filter((p) => {
      if (company && (p.companyName ?? p.companyLabel) !== company) return false;
      if (needle) {
        const hay = `${p.name ?? ''} ${p.pin} ${p.email ?? ''} ${p.team ?? ''}`.toLowerCase();
        if (!hay.includes(needle)) return false;
      }
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
    });
  }, [people, filter, company, search]);

  const grouping = React.useMemo(() => groupingFor(axis, sort), [axis, sort]);

  if (query.isLoading) return <TableSkeleton rows={8} cols={5} />;
  if (query.isError || !query.data) return <ConsoleError error={query.error} />;

  // EMPTY STATE — the roster has never been imported. The first screen on day one.
  if (people.length === 0) {
    return (
      <>
        <EmptyState
          icon={Users}
          title="The roster is empty"
          description="Import the people enrolled on the terminals. Every punch already captured resolves retroactively once they exist — nothing recorded is lost."
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
      {/* Sticky toolbar: every filter is a searchable dropdown, per the standard. */}
      <div className="sticky top-0 z-10 -mx-1 flex flex-wrap items-center gap-2 bg-background/95 px-1 py-2 backdrop-blur">
        <div className="relative min-w-0 flex-1 sm:max-w-xs">
          <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
          <Input
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search name, pin, email…"
            className="pl-9"
            aria-label="Search people"
          />
        </div>
        <Combobox
          ariaLabel="Filter people"
          options={FILTERS.map((f) => ({ value: f.value, label: f.label }))}
          value={filter}
          onChange={(v) => setFilter(v as Filter)}
          className="w-52"
        />
        {companies.length > 1 ? (
          <Combobox
            ariaLabel="Filter by company"
            options={companies.map((c) => ({ value: c, label: c }))}
            value={company}
            onChange={setCompany}
            emptyOptionLabel="All companies"
            className="w-56"
          />
        ) : null}
        <Combobox
          ariaLabel="Group people by"
          options={AXES.map((a) => ({ value: a.value, label: a.label }))}
          value={axis}
          onChange={(v) => setAxis(v as Axis)}
          className="w-48"
        />
        <Combobox
          ariaLabel="Sort people by"
          options={SORTS.map((s) => ({ value: s.value, label: s.label }))}
          value={sort}
          onChange={(v) => setSort(v as SortBy)}
          className="w-44"
        />
        <span className="ml-auto inline-flex items-center gap-2 text-sm tabular-nums text-muted-foreground">
          <ArrowDownUp className="size-3.5" aria-hidden />
          {rows.length === people.length
            ? `${people.length} on the roster`
            : `${rows.length} of ${people.length}`}
        </span>
        <Button size="sm" onClick={() => setAddingPerson(true)}>
          <UserPlus className="size-4" />
          Add person
        </Button>
        <Button variant="outline" size="sm" onClick={() => setImportOpen(true)}>
          <Upload className="size-4" />
          Import
        </Button>
        <CommandChannelBadge />
      </div>

      {picked.size > 0 ? (
        <div className="flex flex-wrap items-center gap-3 rounded-xl border border-primary/25 bg-primary/5 px-4 py-2.5">
          <span className="text-sm font-medium tabular-nums">{picked.size} selected</span>
          <Button size="sm" onClick={() => setBulkOpen(true)}>
            <CalendarClock className="mr-1.5 size-4" aria-hidden />
            Assign shift
          </Button>
          <Button size="sm" variant="ghost" onClick={() => setPicked(new Set())}>
            Clear
          </Button>
          <span className="ml-auto text-xs text-muted-foreground">
            Selection follows the current filter — clearing it is one click.
          </span>
        </div>
      ) : null}

      <GroupedList
        items={rows}
        grouping={grouping}
        storageKey={`people.${axis}`}
        itemKey={(p) => p.id}
        renderItem={(p) => (
          <PersonRow
            person={p}
            siteId={siteId}
            onLink={setLinking}
            onEdit={setEditing}
            onDelete={setDeleting}
            selected={picked.has(p.id)}
            onSelect={(id, checked) =>
              setPicked((prev) => {
                const next = new Set(prev);
                if (checked) next.add(id);
                else next.delete(id);
                return next;
              })
            }
          />
        )}
        empty={
          <EmptyState
            icon={UserRound}
            title="Nobody matches that"
            description="Try a different filter, or clear the search."
            className="py-10"
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
      <BulkShiftDialog
        siteId={siteId}
        people={rows.filter((p) => picked.has(p.id))}
        open={bulkOpen}
        onOpenChange={setBulkOpen}
        onDone={() => setPicked(new Set())}
      />
      <PersonEditDialog
        siteId={siteId}
        person={editing}
        open={editing != null}
        onOpenChange={(o) => !o && setEditing(null)}
      />
      {/* The SAME dialog as the row edit and the inbox flow, with nothing pre-filled. One component
          means a field added for one entry point cannot go missing from the other two. */}
      <PersonEditDialog
        siteId={siteId}
        person={null}
        open={addingPerson}
        onOpenChange={setAddingPerson}
      />
      <DeletePersonDialog
        person={deleting}
        open={Boolean(deleting)}
        onOpenChange={(open) => {
          if (!open) setDeleting(null);
        }}
      />
    </div>
  );
}
