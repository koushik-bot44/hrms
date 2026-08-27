'use client';

import * as React from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { CalendarClock, Moon, Sun } from 'lucide-react';
import {
  assignShift,
  iclockKeys,
  SHIFT_LABELS,
  type AssignShiftReport,
  type IclockPerson,
} from '@/lib/api/iclock';
import { useApiMutation } from '@/lib/api/hooks';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Combobox } from '@/components/console/combobox';
import { cn } from '@/lib/utils';

const SHIFT_OPTIONS = Object.entries(SHIFT_LABELS).map(([value, label]) => ({ value, label }));

/** A compact shift marker for the roster row — icon plus short word, not the full parenthetical. */
export function ShiftBadge({ profile }: { profile: string }) {
  const day = profile === 'DAY';
  const Icon = day ? Sun : Moon;
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-xs',
        day
          ? 'border-warning/30 bg-warning/10 text-warning'
          : 'border-border bg-muted text-muted-foreground',
      )}
      title={SHIFT_LABELS[profile] ?? profile}
    >
      <Icon className="size-3" aria-hidden />
      {day ? 'Day' : 'Night'}
    </span>
  );
}

/**
 * Inline shift change from the roster row.
 *
 * <p>Writes through the SAME bulk endpoint the multi-select uses, with a list of one. A separate
 * single-person path would be a second place for the re-date to be forgotten, and forgetting it is
 * exactly the failure that leaves somebody's month filed against the wrong cut.
 */
export function InlineShiftPicker({
  siteId,
  person,
}: {
  siteId: string;
  person: IclockPerson;
}) {
  const qc = useQueryClient();
  const [pending, setPending] = React.useState<string | null>(null);

  const assign = useApiMutation(
    (profile: string) => assignShift(siteId, [person.id], profile),
    {
      successMessage: (r: AssignShiftReport) =>
        r.changed === 0
          ? 'Already on that shift.'
          : r.punchesRedated > 0
            ? `Moved to ${SHIFT_LABELS[r.shiftProfile]}. ${r.punchesRedated} punch(es) re-dated for this cycle.`
            : `Moved to ${SHIFT_LABELS[r.shiftProfile]}.`,
      onSuccess: () => {
        setPending(null);
        qc.invalidateQueries({ queryKey: iclockKeys.root });
      },
      onError: () => setPending(null),
    },
  );

  return (
    <Combobox
      ariaLabel={`Shift for ${person.name ?? person.pin}`}
      options={SHIFT_OPTIONS}
      value={pending ?? person.shiftProfile}
      onChange={(v) => {
        if (!v || v === person.shiftProfile) return;
        setPending(v);
        assign.mutate(v);
      }}
      disabled={assign.isPending}
      className="h-7 min-w-[9.5rem] text-xs"
      placeholder="Shift"
      searchPlaceholder="Search shifts…"
    />
  );
}

/**
 * Bulk shift assignment over a selection.
 *
 * <p>Exists because the real input is a confirmed LIST — "these twenty-two work days" — and applying
 * it one row at a time across a 150-person roster invites a half-finished assignment where some of a
 * team is on one shift and the rest on another.
 */
export function BulkShiftDialog({
  siteId,
  people,
  open,
  onOpenChange,
  onDone,
}: {
  siteId: string;
  people: IclockPerson[];
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onDone?: () => void;
}) {
  const qc = useQueryClient();
  const [profile, setProfile] = React.useState('DAY');
  const [report, setReport] = React.useState<AssignShiftReport | null>(null);

  React.useEffect(() => {
    if (open) setReport(null);
  }, [open]);

  const assign = useApiMutation(
    () => assignShift(siteId, people.map((p) => p.id), profile),
    {
      onSuccess: (r: AssignShiftReport) => {
        setReport(r);
        qc.invalidateQueries({ queryKey: iclockKeys.root });
        onDone?.();
      },
    },
  );

  const wouldChange = people.filter((p) => p.shiftProfile !== profile).length;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>Assign shift</DialogTitle>
          <DialogDescription>
            {people.length} selected. Their shift decides which day their punches file under, when
            they count as late, and when a long absence is worth alerting on.
          </DialogDescription>
        </DialogHeader>

        {report ? (
          <div className="space-y-3">
            <div className="rounded-lg border border-border bg-card p-3 text-sm">
              <p>
                <strong className="tabular-nums">{report.changed}</strong> moved to{' '}
                {SHIFT_LABELS[report.shiftProfile]}
                {report.alreadyOnIt > 0 ? (
                  <>
                    {' '}· <span className="text-muted-foreground">
                      {report.alreadyOnIt} already there
                    </span>
                  </>
                ) : null}
              </p>
              <p className="mt-1 text-xs text-muted-foreground">
                {report.punchesRedated > 0
                  ? `${report.punchesRedated} punch(es) re-dated from ${report.recomputedFrom}. Earlier cycles were left alone.`
                  : 'No punches needed re-dating.'}
              </p>
            </div>
            <div className="flex justify-end">
              <Button onClick={() => onOpenChange(false)}>Done</Button>
            </div>
          </div>
        ) : (
          <form
            className="space-y-4"
            onSubmit={(e) => {
              e.preventDefault();
              assign.mutate();
            }}
          >
            <div className="space-y-1.5">
              <label className="text-sm font-medium">Shift</label>
              <Combobox
                ariaLabel="Shift to assign"
                options={SHIFT_OPTIONS}
                value={profile}
                onChange={(v) => setProfile(v || 'DAY')}
                searchPlaceholder="Search shifts…"
              />
            </div>

            <p className="rounded-lg border border-border bg-muted/40 p-3 text-xs text-muted-foreground">
              <CalendarClock className="mr-1.5 inline size-3.5" aria-hidden />
              {wouldChange === 0
                ? 'Everyone selected is already on this shift — nothing will change.'
                : `${wouldChange} will move. Their punches for the current payroll cycle are re-dated to match; earlier cycles are never touched.`}
            </p>

            <div className="flex flex-wrap justify-end gap-2 border-t border-border pt-4">
              <Button type="button" variant="ghost" onClick={() => onOpenChange(false)}>
                Cancel
              </Button>
              <Button type="submit" disabled={assign.isPending || people.length === 0}>
                {assign.isPending ? 'Assigning…' : `Assign ${people.length}`}
              </Button>
            </div>
          </form>
        )}
      </DialogContent>
    </Dialog>
  );
}
