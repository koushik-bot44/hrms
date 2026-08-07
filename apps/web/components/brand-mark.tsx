import * as React from 'react';
import { cn } from '@/lib/utils';

/**
 * The hrorg.in brand mark — the logo raster, rendered at a fixed size with explicit width/height (no layout
 * shift). The source has a BAKED dark-navy background (fully opaque), so the mark must sit on a dark surface:
 * every render point places it in a rounded tile whose radius clips the navy corners, so there is no white
 * box and no visible seam. A plain <img> (per the brief's allowed option) keeps it usable in server and client
 * components with no next/image config. Always alt="hrorg.in".
 */
export function BrandMark({
  size = 36,
  className,
  rounded = 'rounded-lg',
}: {
  size?: number;
  className?: string;
  /** Tailwind radius utility to match the tile it replaces (e.g. rounded-md/lg/xl/2xl). */
  rounded?: string;
}) {
  return (
    // eslint-disable-next-line @next/next/no-img-element
    <img
      src="/brand/logo.png"
      alt="hrorg.in"
      width={size}
      height={size}
      className={cn('block shrink-0 object-cover', rounded, className)}
      style={{ width: size, height: size }}
    />
  );
}
