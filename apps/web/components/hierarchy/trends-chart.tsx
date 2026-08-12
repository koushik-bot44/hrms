'use client';

import {
  Area,
  AreaChart,
  CartesianGrid,
  Legend,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import { monthLabel } from '@/lib/date';

/**
 * The recharts area chart for HierarchyTrends, split into its own module so recharts stays OUT of the
 * /hierarchy first-load bundle (lazy-loaded by the parent once there's data to draw). Pure presentational —
 * the card shell, range toggle, loading skeleton and empty states all live in the parent (no visual change).
 */
const JOINED = 'hsl(var(--primary))';
const APPROVED = 'hsl(var(--success))';
const OFFBOARDED = 'hsl(var(--muted-foreground))';

/** Human label for a raw series key (drives both the legend and the tooltip so they never drift). */
function seriesLabel(key: string): string {
  if (key === 'joined') return 'Onboarded';
  if (key === 'approved') return 'Approved';
  return 'Offboarded';
}

export interface TrendPointRow {
  month: string;
  key: string;
  joined: number;
  approved: number;
  offboarded: number;
}

export default function TrendsChart({ data }: { data: TrendPointRow[] }) {
  return (
    <div className="h-64">
      <ResponsiveContainer width="100%" height="100%">
        <AreaChart data={data} margin={{ top: 4, right: 8, left: -16, bottom: 0 }}>
          <defs>
            {/* Soft token-derived gradient fills — indigo for onboarded, green for approved. */}
            <linearGradient id="areaJoined" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor={JOINED} stopOpacity={0.35} />
              <stop offset="100%" stopColor={JOINED} stopOpacity={0} />
            </linearGradient>
          </defs>
          <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="hsl(var(--border))" />
          <XAxis dataKey="month" tick={{ fontSize: 11, fill: 'hsl(var(--muted-foreground))' }} axisLine={false} tickLine={false} interval="preserveStartEnd" />
          <YAxis allowDecimals={false} tick={{ fontSize: 11, fill: 'hsl(var(--muted-foreground))' }} axisLine={false} tickLine={false} width={36} />
          <Tooltip
            cursor={{ stroke: 'hsl(var(--border))' }}
            formatter={(v, n) => [String(v), seriesLabel(String(n))]}
            labelFormatter={(_l, p) => (p?.[0]?.payload ? monthLabel(p[0].payload.key) : '')}
            contentStyle={{ borderRadius: 'var(--radius)', border: '1px solid hsl(var(--border))', background: 'hsl(var(--card))', fontSize: 12 }}
          />
          <Legend
            verticalAlign="top"
            height={28}
            formatter={(value) => seriesLabel(String(value))}
            wrapperStyle={{ fontSize: 12 }}
          />
          {/* Onboarded is the volume baseline (filled); approved + offboarded ride on top as LINES
              (no second fill) so the three series never occlude each other. */}
          <Area type="monotone" dataKey="joined" name="joined" stroke={JOINED} strokeWidth={2} fill="url(#areaJoined)" />
          <Area type="monotone" dataKey="approved" name="approved" stroke={APPROVED} strokeWidth={2} fill="none" fillOpacity={0} />
          <Area type="monotone" dataKey="offboarded" name="offboarded" stroke={OFFBOARDED} strokeWidth={2} strokeDasharray="4 3" fill="none" fillOpacity={0} />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  );
}
