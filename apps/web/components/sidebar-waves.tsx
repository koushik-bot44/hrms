import * as React from 'react';
import { cn } from '@/lib/utils';

/**
 * Static decorative wave-line texture for a sidebar's lower region — a mesh of fine concentric indigo arcs
 * flowing diagonally (the design target). A REAL drawn-lines SVG: a gradient can't produce this texture.
 * Pinned to the bottom, sits BEHIND the badge/collapse content (no z-index, painted first + siblings are
 * relative), pointer-events-none, aria-hidden. Colour is the indigo brand token (currentColor via
 * `text-primary-bright`), so it reads on the navy sidebar in both app modes. Meant to live inside a
 * `relative overflow-hidden` sidebar so it clips cleanly.
 */
export function SidebarWaves({ className }: { className?: string }) {
  // Concentric circles centred just past the bottom-right corner (250,430 in a 240x400 viewBox); the
  // visible upper-left quarter of each sweeps diagonally across the lower sidebar. Opacity steps DOWN with
  // radius so the nearer (tighter, lower) arcs read slightly stronger — a present-but-subtle texture.
  const CX = 250;
  const CY = 430;
  const radii = Array.from({ length: 12 }, (_, i) => 96 + i * 22); // 96 … 338
  const last = radii.length - 1;

  return (
    <svg
      aria-hidden="true"
      viewBox="0 0 240 400"
      preserveAspectRatio="xMidYMax slice"
      className={cn('pointer-events-none absolute inset-x-0 bottom-0 h-2/5 w-full text-primary-bright', className)}
    >
      {radii.map((r, i) => (
        <path
          key={r}
          d={`M ${CX - r} ${CY} A ${r} ${r} 0 0 1 ${CX} ${CY - r}`}
          fill="none"
          stroke="currentColor"
          strokeWidth={1.25}
          // 0.30 (nearest) → 0.12 (farthest), stepped evenly across the 12 arcs — present-but-subtle on navy.
          strokeOpacity={0.3 - (i / last) * 0.18}
        />
      ))}
    </svg>
  );
}
