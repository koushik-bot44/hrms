'use client';

import { cn } from '@/lib/utils';
import type { IclockSite } from '@/lib/api/iclock';

const SELECT_CLASS =
  'h-11 rounded-md border border-input bg-background px-3 text-sm ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2';

/**
 * Site scope selector. Hidden when there is exactly one site — a picker with one option is furniture
 * that teaches the operator nothing, and Orion Towers is the only site on day one.
 */
export function SitePicker({
  sites,
  siteId,
  onChange,
  className,
}: {
  sites: IclockSite[];
  siteId: string | null;
  onChange: (siteId: string) => void;
  className?: string;
}) {
  if (sites.length <= 1) return null;
  return (
    <select
      className={cn(SELECT_CLASS, className)}
      value={siteId ?? ''}
      onChange={(e) => onChange(e.target.value)}
      aria-label="Site"
    >
      {sites.map((s) => (
        <option key={s.id} value={s.id}>
          {s.name}
        </option>
      ))}
    </select>
  );
}
