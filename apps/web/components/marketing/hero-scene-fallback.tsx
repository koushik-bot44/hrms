import * as React from 'react';
import { cn } from '@/lib/utils';

/**
 * Static SVG fallback for the hero scene — shown while the interactive canvas chunk loads AND whenever
 * `prefers-reduced-motion` is set. An abstract indigo "network orb": a glowing core, concentric rings, a
 * deterministic constellation of nodes + connectors, and a faint dotted field. Evolves the AuthIllustration
 * language. Token-coloured (currentColor via text-primary-bright), aria-hidden, pointer-events-none, and
 * fully deterministic (no randomness) so it renders identically everywhere.
 */

const NODES = [
  { x: 250, y: 120 },
  { x: 400, y: 190 },
  { x: 360, y: 340 },
  { x: 210, y: 300 },
  { x: 120, y: 210 },
  { x: 320, y: 250 },
  { x: 180, y: 130 },
  { x: 430, y: 300 },
] as const;

const EDGES: [number, number][] = [
  [0, 1],
  [1, 5],
  [5, 2],
  [2, 3],
  [3, 4],
  [4, 6],
  [6, 0],
  [5, 3],
  [1, 7],
  [7, 2],
];

const DOTS = Array.from({ length: 54 }, (_, i) => ({
  x: ((i * 137 + 30) % 460) + 20,
  y: ((i * 79 + 44) % 380) + 20,
}));

function edgePath(a: (typeof NODES)[number], b: (typeof NODES)[number]): string {
  const cx = (a.x + b.x) / 2 + (b.y - a.y) * 0.16;
  const cy = (a.y + b.y) / 2 - (b.x - a.x) * 0.16;
  return `M ${a.x} ${a.y} Q ${cx.toFixed(1)} ${cy.toFixed(1)} ${b.x} ${b.y}`;
}

export function HeroSceneFallback({ className }: { className?: string }) {
  return (
    <svg
      aria-hidden="true"
      viewBox="0 0 500 440"
      preserveAspectRatio="xMidYMid meet"
      className={cn('pointer-events-none h-full w-full text-primary-bright', className)}
    >
      <defs>
        <radialGradient id="m-core" cx="50%" cy="45%" r="50%">
          <stop offset="0%" stopColor="currentColor" stopOpacity={0.55} />
          <stop offset="45%" stopColor="currentColor" stopOpacity={0.12} />
          <stop offset="100%" stopColor="currentColor" stopOpacity={0} />
        </radialGradient>
      </defs>

      {/* Core glow. */}
      <circle cx={300} cy={230} r={180} fill="url(#m-core)" />

      {/* Concentric rings. */}
      {[70, 120, 172].map((r, i) => (
        <circle
          key={r}
          cx={300}
          cy={230}
          r={r}
          fill="none"
          stroke="currentColor"
          strokeOpacity={0.16 - i * 0.03}
          strokeWidth={1}
        />
      ))}

      {/* Dotted field. */}
      {DOTS.map((d, i) => (
        <circle key={`d${i}`} cx={d.x} cy={d.y} r={1.5} fill="currentColor" fillOpacity={0.12} />
      ))}

      {/* Connectors. */}
      {EDGES.map(([a, b], i) => (
        <path
          key={`e${i}`}
          d={edgePath(NODES[a], NODES[b])}
          fill="none"
          stroke="currentColor"
          strokeOpacity={0.28}
          strokeWidth={1}
        />
      ))}

      {/* Nodes. */}
      {NODES.map((n, i) => (
        <g key={`n${i}`}>
          <circle
            cx={n.x}
            cy={n.y}
            r={11}
            fill="currentColor"
            fillOpacity={0.14}
            stroke="currentColor"
            strokeOpacity={0.5}
            strokeWidth={1.1}
          />
          <circle cx={n.x} cy={n.y} r={2.4} fill="currentColor" fillOpacity={0.95} />
        </g>
      ))}
    </svg>
  );
}
