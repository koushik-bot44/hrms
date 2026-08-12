'use client';

import { Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';

/**
 * The month-wise absences/late-logins bar chart for the attendance MonthlyReport, split out so recharts
 * stays OUT of the accountant + manager/attendance first-load bundles (it's the only recharts importer in
 * that tree). Fixed h-40 box, matched by the parent's lazy skeleton — no layout shift, no visual change.
 */
export interface MonthReportRow {
  month: string;
  key: string;
  absences: number;
  late: number;
}

export default function MonthReportChart({ data }: { data: MonthReportRow[] }) {
  return (
    <div className="h-40">
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={data} margin={{ top: 4, right: 8, left: -16, bottom: 0 }} barGap={2}>
          <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="hsl(var(--border))" />
          <XAxis dataKey="month" tick={{ fontSize: 11, fill: 'hsl(var(--muted-foreground))' }} axisLine={false} tickLine={false} />
          <YAxis allowDecimals={false} tick={{ fontSize: 11, fill: 'hsl(var(--muted-foreground))' }} axisLine={false} tickLine={false} width={36} />
          <Tooltip
            cursor={{ fill: 'hsl(var(--accent))', opacity: 0.4 }}
            formatter={(v, n) => [String(v), n === 'absences' ? 'Unapproved absences' : 'Late logins']}
            contentStyle={{ borderRadius: 'var(--radius)', border: '1px solid hsl(var(--border))', background: 'hsl(var(--card))', fontSize: 12 }}
          />
          <Legend
            verticalAlign="top"
            height={24}
            formatter={(value) => (value === 'absences' ? 'Unapproved absences' : 'Late logins')}
            wrapperStyle={{ fontSize: 12 }}
          />
          <Bar dataKey="absences" name="absences" fill="hsl(var(--destructive))" radius={[3, 3, 0, 0]} />
          <Bar dataKey="late" name="late" fill="hsl(var(--warning))" radius={[3, 3, 0, 0]} />
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}
