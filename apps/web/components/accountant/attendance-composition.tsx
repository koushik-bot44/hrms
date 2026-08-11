'use client';

import { AlarmClock, CalendarCheck2, CalendarOff, CalendarX2, Clock, Coffee } from 'lucide-react';
import { formatDuration } from '@/lib/date';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { StatTile } from '@/components/dashboard/stat-tile';
import { EmptyState } from '@/components/empty-state';
import { cn } from '@/lib/utils';

// Theme-aware chart colours from the hrorg.in design tokens (they recolor with the theme).
const SEG_PRESENT = 'hsl(var(--success))';
const SEG_LEAVE = 'hsl(var(--primary))';
const SEG_ABSENT = 'hsl(var(--destructive))';

const hm = (seconds: number) => formatDuration(seconds);

/** The metric shape both the employee detail and the team roll-up feed into the composition view. */
export interface CompositionData {
  workedSeconds: number;
  breakSeconds: number;
  daysPresent: number;
  lateLogins: number;
  leaveDaysTotal: number;
  leavesByType: { casual: number; sick: number; unpaid: number };
  expectedDays: number;
  /** The window's working-day count (employee summary carries it; team omits it → sub-label adapts). */
  workingDays?: number | null;
  unapprovedAbsences: number;
  adherencePct: number | null;
  workingDaysDefinition?: string;
}

/**
 * The shared attendance view (§8a): an ADHERENCE lead visual — the headline adherence % plus a segmented bar
 * of how the window's expected working days actually broke down (present / leave / unapproved absent) — beside
 * the count stat tiles. Mounted by BOTH the employee detail and the team roll-up (identical layout; the team
 * feeds summed numbers). Replaces the old worked-vs-break donut, whose ~99/1 ratio looked the same for
 * everyone; adherence varies strongly between a healthy and a poor month. In Today mode the caller hides the
 * adherence visual (a one-day fraction is meaningless) and the Unapproved-absences tile (past-days-only rule),
 * and relabels the late tile ("Late today").
 */
export function AttendanceComposition({
  data,
  lateLabel = 'Late logins',
  daysPresentLabel = 'Days present',
  hideAbsences = false,
  hideAdherence = false,
}: {
  data: CompositionData;
  lateLabel?: string;
  daysPresentLabel?: string;
  hideAbsences?: boolean;
  hideAdherence?: boolean;
}) {
  const showAdherence = !hideAdherence;

  return (
    <div className="grid gap-4 lg:grid-cols-3">
      {showAdherence ? <AdherenceCard data={data} /> : null}

      {/* Counts / durations — the exact numbers behind the visual. */}
      <div
        className={cn(
          'grid grid-cols-2 gap-4 sm:grid-cols-3',
          showAdherence ? 'lg:col-span-2' : 'lg:col-span-3',
        )}
      >
        <StatTile icon={Clock} label="Worked" value={hm(data.workedSeconds)} tone="primary" size="sm" />
        <StatTile icon={Coffee} label="Break time" value={hm(data.breakSeconds)} tone="neutral" size="sm" />
        <StatTile icon={CalendarCheck2} label={daysPresentLabel} value={String(data.daysPresent)} tone="primary" size="sm" />
        <StatTile
          icon={AlarmClock}
          label={lateLabel}
          value={String(data.lateLogins)}
          tone={data.lateLogins > 0 ? 'warning' : 'neutral'}
          size="sm"
        />
        {hideAbsences ? null : (
          <StatTile
            icon={CalendarX2}
            label="Unapproved Absences"
            value={String(data.unapprovedAbsences)}
            tone={data.unapprovedAbsences > 0 ? 'danger' : 'neutral'}
            size="sm"
            sub="Past working days, no session or leave"
            title={data.workingDaysDefinition}
          />
        )}
        <StatTile
          icon={CalendarOff}
          label="Leaves taken"
          value={String(data.leaveDaysTotal)}
          tone="neutral"
          size="sm"
          sub={`C ${data.leavesByType.casual} · S ${data.leavesByType.sick} · U ${data.leavesByType.unpaid}`}
        />
      </div>
    </div>
  );
}

/**
 * The adherence lead card: the backend-authoritative adherence % (present ÷ expected working days) as a
 * tone-coloured headline, over a segmented bar of the window's expected working days — Present (green) / Leave
 * (indigo) / Unapproved absent (red), with the remaining track = working days not yet due. This is the metric
 * that actually moves: a healthy month is near-all green, a poor one shows a clear red chunk.
 */
function AdherenceCard({ data }: { data: CompositionData }) {
  const present = data.daysPresent;
  const leave = data.leaveDaysTotal;
  const absent = data.unapprovedAbsences;
  const accounted = present + leave + absent;
  // Never overflow the bar: total is at least the accounted days (present on non-working days can exceed
  // expected), and at least the expected working-day count so partial months show the unfilled remainder.
  const total = Math.max(data.expectedDays, accounted);
  const hasDays = total > 0;

  const segs = [
    { key: 'present', label: 'Present', value: present, color: SEG_PRESENT },
    { key: 'leave', label: 'Leave', value: leave, color: SEG_LEAVE },
    { key: 'absent', label: 'Absent', value: absent, color: SEG_ABSENT },
  ];

  const adh = data.adherencePct;
  const toneClass =
    adh === null
      ? 'text-muted-foreground'
      : adh >= 90
        ? 'text-success'
        : adh >= 75
          ? 'text-warning'
          : 'text-destructive';

  return (
    <Card className="lg:col-span-1">
      <CardHeader>
        <CardTitle className="text-base">Adherence</CardTitle>
      </CardHeader>
      <CardContent>
        {!hasDays ? (
          <EmptyState
            icon={CalendarCheck2}
            title="No working days"
            description="No expected working days in this window."
          />
        ) : (
          <div className="space-y-4" title={data.workingDaysDefinition}>
            <div>
              <p className={cn('text-3xl font-semibold tracking-tight tabular-nums', toneClass)}>
                {adh === null ? 'N/A' : `${adh}%`}
              </p>
              <p className="text-xs text-muted-foreground">
                {adh === null
                  ? 'No expected working days'
                  : `${present} of ${data.expectedDays} expected working days${
                      data.workingDays != null ? ` · ${data.workingDays} working days` : ''
                    }`}
              </p>
            </div>
            <div
              className="flex h-3 w-full overflow-hidden rounded-full bg-muted"
              role="img"
              aria-label={`${present} present, ${leave} on leave, ${absent} absent of ${data.expectedDays} working days`}
            >
              {segs
                .filter((s) => s.value > 0)
                .map((s) => (
                  <div key={s.key} style={{ width: `${(s.value / total) * 100}%`, background: s.color }} />
                ))}
            </div>
            <ul className="grid grid-cols-3 gap-2 text-xs">
              {segs.map((s) => (
                <li key={s.key} className="space-y-0.5">
                  <span className="flex items-center gap-1.5 text-muted-foreground">
                    <span className="size-2 rounded-full" style={{ background: s.color }} />
                    {s.label}
                  </span>
                  <span className="block pl-3.5 font-semibold tabular-nums text-foreground">{s.value}</span>
                </li>
              ))}
            </ul>
          </div>
        )}
      </CardContent>
    </Card>
  );
}
