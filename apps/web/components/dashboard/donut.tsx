'use client';

import * as React from 'react';
import { Cell, Pie, PieChart, ResponsiveContainer, Tooltip } from 'recharts';
import { cn } from '@/lib/utils';

/**
 * A shared donut wrapper (design system) — recharts, token colours. Supports a CENTER TOTAL: a big value +
 * small label rendered inside the ring (e.g. total clocked time = worked + break). REAL data only; the
 * caller supplies the segments and, if wanted, the pre-formatted center value/label. Segment colours are
 * CSS colour strings from tokens (e.g. `hsl(var(--primary))`), matching the existing charts.
 */
export interface DonutSegment {
  name: string;
  value: number;
  /** A CSS colour — pass a token, e.g. `hsl(var(--primary))`. */
  color: string;
}

export function Donut({
  data,
  centerValue,
  centerLabel,
  tooltipFormatter,
  innerRadius = 52,
  outerRadius = 76,
  className,
}: {
  data: DonutSegment[];
  centerValue?: React.ReactNode;
  centerLabel?: React.ReactNode;
  /** recharts Tooltip formatter — returns `[value, name]`. */
  tooltipFormatter?: (value: number, name: string) => [React.ReactNode, React.ReactNode];
  innerRadius?: number;
  outerRadius?: number;
  className?: string;
}) {
  return (
    <div className={cn('relative h-44', className)}>
      <ResponsiveContainer width="100%" height="100%">
        <PieChart>
          <Pie
            data={data}
            dataKey="value"
            nameKey="name"
            innerRadius={innerRadius}
            outerRadius={outerRadius}
            paddingAngle={2}
            strokeWidth={0}
          >
            {data.map((d) => (
              <Cell key={d.name} fill={d.color} />
            ))}
          </Pie>
          {tooltipFormatter ? (
            <Tooltip
              formatter={(v, n) => tooltipFormatter(Number(v), String(n))}
              contentStyle={{
                borderRadius: 'var(--radius)',
                border: '1px solid hsl(var(--border))',
                background: 'hsl(var(--card))',
                fontSize: 12,
              }}
            />
          ) : null}
        </PieChart>
      </ResponsiveContainer>
      {centerValue != null ? (
        <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center text-center">
          <span className="text-xl font-semibold tracking-tight tabular-nums">{centerValue}</span>
          {centerLabel != null ? (
            <span className="text-[11px] text-muted-foreground">{centerLabel}</span>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}
