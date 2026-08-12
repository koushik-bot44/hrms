'use client';

import { Bar, BarChart, Cell, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';

/**
 * The recharts horizontal bar chart for CompanyBreakdownPanel, split out so recharts stays OUT of the
 * /hierarchy first-load bundle. The parent keeps the height-reserving wrapper (its height is data-derived),
 * so the lazy fallback fills the exact same box — no layout shift, no visual change.
 */
const ACTIVE = 'hsl(var(--primary))';
const ARCHIVED = 'hsl(var(--muted-foreground))';

export interface CompanyBarRow {
  name: string;
  key: string;
  value: number;
  archived: boolean;
}

export default function CompaniesBarChart({ data }: { data: CompanyBarRow[] }) {
  return (
    <ResponsiveContainer width="100%" height="100%">
      <BarChart layout="vertical" data={data} margin={{ top: 0, right: 16, left: 8, bottom: 0 }}>
        <XAxis type="number" allowDecimals={false} tick={{ fontSize: 11, fill: 'hsl(var(--muted-foreground))' }} axisLine={false} tickLine={false} />
        <YAxis type="category" dataKey="name" width={104} tickFormatter={(v: string) => (v.length > 13 ? `${v.slice(0, 12)}…` : v)} tick={{ fontSize: 11, fill: 'hsl(var(--muted-foreground))' }} axisLine={false} tickLine={false} />
        <Tooltip
          cursor={{ fill: 'hsl(var(--accent))', opacity: 0.4 }}
          formatter={(v) => [String(v), 'Employees']}
          labelFormatter={(l, p) => (p?.[0]?.payload?.archived ? `${l} (archived)` : String(l))}
          contentStyle={{ borderRadius: 'var(--radius)', border: '1px solid hsl(var(--border))', background: 'hsl(var(--card))', fontSize: 12 }}
        />
        <Bar dataKey="value" radius={[0, 3, 3, 0]}>
          {data.map((d) => (
            <Cell key={d.key} fill={d.archived ? ARCHIVED : ACTIVE} />
          ))}
        </Bar>
      </BarChart>
    </ResponsiveContainer>
  );
}
