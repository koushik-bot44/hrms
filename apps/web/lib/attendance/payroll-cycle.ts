/**
 * Payroll cycle boundaries — the universal 26th → 25th window (§8a). Pure calendar math over YYYY-MM-DD
 * strings (no `Date`, no timezone), so it is deterministic and unit-testable and can't drift across the IST
 * boundary. The current cycle is chosen from today's day-of-month; stepping shifts one whole cycle and
 * spans month/year boundaries naturally (Dec 26 → Jan 25). A cycle is just a [from,to] range — the API and
 * every downstream metric treat it exactly like a custom range.
 */

export interface CycleRange {
  from: string;
  to: string;
}

interface Ymd {
  y: number;
  m: number; // 1..12
  d: number;
}

function parse(iso: string): Ymd {
  const [y, m, d] = iso.split('-').map(Number);
  return { y, m, d };
}

function pad(n: number): string {
  return String(n).padStart(2, '0');
}

function fmt(y: number, m: number, d: number): string {
  return `${y}-${pad(m)}-${pad(d)}`;
}

/** Shift a (year, 1-based month) by `delta` months, normalizing the year. */
function shiftMonth(y: number, m: number, delta: number): { y: number; m: number } {
  const total = y * 12 + (m - 1) + delta;
  return { y: Math.floor(total / 12), m: (total % 12) + 1 };
}

/**
 * The payroll cycle CONTAINING `today` (YYYY-MM-DD): if day-of-month ≥ 26 →
 * [this-month-26 .. next-month-25]; else [prev-month-26 .. this-month-25].
 */
export function currentCycle(today: string): CycleRange {
  const { y, m, d } = parse(today);
  const start = d >= 26 ? { y, m } : shiftMonth(y, m, -1);
  const end = shiftMonth(start.y, start.m, 1);
  return { from: fmt(start.y, start.m, 26), to: fmt(end.y, end.m, 25) };
}

/** Shift a whole cycle by `delta` (−1 = previous, +1 = next). `from` must be a cycle start (a 26th). */
export function stepCycle(from: string, delta: number): CycleRange {
  const { y, m } = parse(from);
  const start = shiftMonth(y, m, delta);
  const end = shiftMonth(start.y, start.m, 1);
  return { from: fmt(start.y, start.m, 26), to: fmt(end.y, end.m, 25) };
}
