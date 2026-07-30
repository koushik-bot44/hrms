'use client';

import { AlarmClock, CalendarCheck2, CalendarOff, CalendarX2, Clock, Coffee, Gauge } from 'lucide-react';
import { formatDuration } from '@/lib/date';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Donut } from '@/components/dashboard/donut';
import { StatTile, MeterRow } from '@/components/dashboard/stat-tile';

// Theme-aware chart colours from the hrorg.in design tokens (work each look via CSS vars).
const WORKED = 'hsl(var(--primary))';
const BREAK = 'hsl(var(--warning))';

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
 * The shared attendance COMPOSITION view (§8a): a worked-vs-break donut with a center total + same-unit
 * meters (the only same-unit split — counts never go in the pie), plus stat tiles for the counts. Mounted
 * by BOTH the employee detail and the team roll-up (identical layout; the team feeds summed numbers). In
 * Today mode the caller hides the Unapproved-absences tile (its rule is past-days-only — a 0 misleads) and
 * the Adherence tile (a one-day fraction is meaningless), and relabels the late tile ("Late today").
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
  const gross = data.workedSeconds + data.breakSeconds;
  const pct = (v: number) => (gross > 0 ? Math.round((v / gross) * 100) : 0);
  const pieData = [
    { name: 'Worked', value: data.workedSeconds, color: WORKED },
    { name: 'Break', value: data.breakSeconds, color: BREAK },
  ];

  return (
    <div className="grid gap-4 lg:grid-cols-3">
      {/* Donut — worked vs break only (sums to gross clocked time). */}
      <Card className="lg:col-span-1">
        <CardHeader>
          <CardTitle className="text-base">Time composition</CardTitle>
        </CardHeader>
        <CardContent>
          {gross === 0 ? (
            <p className="py-8 text-center text-sm text-muted-foreground">No clocked time in this window.</p>
          ) : (
            <>
              <Donut
                data={pieData}
                centerValue={hm(gross)}
                centerLabel="Total time"
                tooltipFormatter={(v, n) => [`${hm(v)} · ${pct(v)}%`, n]}
              />
              <div className="mt-3 space-y-3">
                <MeterRow
                  label="Worked"
                  value={data.workedSeconds}
                  max={gross}
                  tone="primary"
                  display={`${hm(data.workedSeconds)} · ${pct(data.workedSeconds)}%`}
                />
                <MeterRow
                  label="Break"
                  value={data.breakSeconds}
                  max={gross}
                  tone="warning"
                  display={`${hm(data.breakSeconds)} · ${pct(data.breakSeconds)}%`}
                />
              </div>
            </>
          )}
        </CardContent>
      </Card>

      {/* Counts / other units — never in the donut. */}
      <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:col-span-2">
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
        {hideAdherence ? null : (
          <StatTile
            icon={Gauge}
            label="Adherence"
            value={data.adherencePct === null ? 'N/A' : `${data.adherencePct}%`}
            tone="primary"
            size="sm"
            sub={
              data.adherencePct === null
                ? 'No expected working days'
                : `of ${data.expectedDays} expected${data.workingDays != null ? ` · ${data.workingDays} working days` : ''}`
            }
            title={data.workingDaysDefinition}
          />
        )}
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
