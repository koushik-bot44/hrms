/**
 * Small date helpers. `relativeTime` renders a compact "…ago" label (Gmail/notification style),
 * falling back to an absolute date past a week.
 */
export function relativeTime(iso: string): string {
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return '';
  const minutes = Math.floor((Date.now() - then) / 60000);
  if (minutes < 1) return 'just now';
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  if (days < 7) return `${days}d ago`;
  return new Date(iso).toLocaleDateString();
}

/** Absolute, human-readable timestamp for tooltips/details. */
export function absoluteTime(iso: string): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? '' : d.toLocaleString();
}

// --- Asia/Kolkata (attendance, §8a) — the app renders all attendance times in IST ------------------

const IST = 'Asia/Kolkata';

/** An instant as a time-of-day in IST, e.g. "09:14 AM". */
export function istTime(iso: string): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime())
    ? ''
    : d.toLocaleTimeString('en-IN', { timeZone: IST, hour: '2-digit', minute: '2-digit' });
}

/** An instant as a full IST timestamp for tooltips, e.g. "10 Jul, 09:14 AM". */
export function istDateTime(iso: string): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime())
    ? ''
    : d.toLocaleString('en-IN', {
        timeZone: IST,
        day: '2-digit',
        month: 'short',
        hour: '2-digit',
        minute: '2-digit',
      });
}

/** A yyyy-MM-dd IST day → a friendly heading, e.g. "Mon, 10 Jul 2026". */
export function istDayLabel(isoDate: string): string {
  // The date is already an IST calendar day; anchor it at IST midnight so the label can't drift.
  const d = new Date(`${isoDate}T00:00:00+05:30`);
  return Number.isNaN(d.getTime())
    ? isoDate
    : d.toLocaleDateString('en-IN', {
        timeZone: IST,
        weekday: 'short',
        day: '2-digit',
        month: 'short',
        year: 'numeric',
      });
}

/** Whole-seconds duration → "2h 34m" (or "0m"); for day/period totals. */
export function formatDuration(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  if (h > 0 && m > 0) return `${h}h ${m}m`;
  if (h > 0) return `${h}h`;
  return `${m}m`;
}

/** Today's date (yyyy-MM-dd) in IST — the default upper bound for attendance filters. */
export function istTodayIso(): string {
  return new Date().toLocaleDateString('en-CA', { timeZone: IST });
}

/** {@code days} ago (yyyy-MM-dd) in IST — the default lower bound for attendance filters. */
export function istDaysAgoIso(days: number): string {
  const d = new Date();
  d.setDate(d.getDate() - days);
  return d.toLocaleDateString('en-CA', { timeZone: IST });
}

/** Whole-seconds elapsed → "H:MM:SS" for the live clocked-in timer. */
export function formatElapsed(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${h}:${pad(m)}:${pad(sec)}`;
}
