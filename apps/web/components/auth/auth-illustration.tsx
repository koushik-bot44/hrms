import * as React from 'react';
import { cn } from '@/lib/utils';

/**
 * Decorative inline-SVG "network / constellation" illustration for the login left panel (NO photos, no
 * external assets). A scattered dotted field + a few node circles (soft indigo fills + a bright core —
 * placeholders, not photographs) connected by thin curved lines, with soft glows. Token-coloured
 * (currentColor via `text-primary-bright`), aria-hidden, pointer-events-none. Deterministic coordinates
 * (no Math.random) so SSR and client render identically. Opacities are tuned to read against the navy
 * panel: dots 0.14, glows 0.07, links 0.30, node-ring fill 0.16 / stroke 0.55, cores 0.9.
 */
const NODES = [
  { x: 74, y: 90 },
  { x: 202, y: 58 },
  { x: 322, y: 110 },
  { x: 142, y: 210 },
  { x: 300, y: 226 },
] as const;

const EDGES: [number, number][] = [
  [0, 1],
  [1, 2],
  [0, 3],
  [3, 4],
  [1, 4],
  [2, 4],
];

// A gentle curved connector between two nodes (perpendicular-offset control point).
function edgePath(a: (typeof NODES)[number], b: (typeof NODES)[number]): string {
  const cx = (a.x + b.x) / 2 + (b.y - a.y) * 0.18;
  const cy = (a.y + b.y) / 2 - (b.x - a.x) * 0.18;
  return `M ${a.x} ${a.y} Q ${cx.toFixed(1)} ${cy.toFixed(1)} ${b.x} ${b.y}`;
}

// Deterministic scattered dot field (coprime steps → no obvious grid; identical every render).
const DOTS = Array.from({ length: 40 }, (_, i) => ({
  x: ((i * 149 + 24) % 372) + 14,
  y: ((i * 83 + 40) % 292) + 14,
}));

export function AuthIllustration({ className }: { className?: string }) {
  return (
    <svg
      aria-hidden="true"
      viewBox="0 0 400 320"
      preserveAspectRatio="xMidYMid meet"
      className={cn('pointer-events-none h-auto w-full text-primary-bright', className)}
    >
      {/* Soft glows behind two anchor nodes. */}
      <circle cx={NODES[0].x} cy={NODES[0].y} r={50} fill="currentColor" fillOpacity={0.07} />
      <circle cx={NODES[2].x} cy={NODES[2].y} r={44} fill="currentColor" fillOpacity={0.07} />

      {/* Scattered dotted field (like a faint map). */}
      {DOTS.map((d, i) => (
        <circle key={`d${i}`} cx={d.x} cy={d.y} r={1.6} fill="currentColor" fillOpacity={0.14} />
      ))}

      {/* Thin curved connectors. */}
      {EDGES.map(([a, b], i) => (
        <path
          key={`e${i}`}
          d={edgePath(NODES[a], NODES[b])}
          fill="none"
          stroke="currentColor"
          strokeOpacity={0.3}
          strokeWidth={1}
        />
      ))}

      {/* Nodes: soft-filled ring + bright core. */}
      {NODES.map((nd, i) => (
        <g key={`n${i}`}>
          <circle
            cx={nd.x}
            cy={nd.y}
            r={13}
            fill="currentColor"
            fillOpacity={0.16}
            stroke="currentColor"
            strokeOpacity={0.55}
            strokeWidth={1.25}
          />
          <circle cx={nd.x} cy={nd.y} r={2.6} fill="currentColor" fillOpacity={0.9} />
        </g>
      ))}
    </svg>
  );
}
