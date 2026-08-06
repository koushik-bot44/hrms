'use client';

import dynamic from 'next/dynamic';
import { HeroSceneFallback } from '@/components/marketing/hero-scene-fallback';

/**
 * Client boundary that lazy-loads the Canvas-2D hero scene with `ssr: false` so it never enters the page's
 * first-load JS and never blocks LCP. While the chunk loads (and forever, under reduced motion) the static
 * SVG fallback shows. Kept as a tiny wrapper so the hero section itself stays a server component.
 */
const HeroScene = dynamic(() => import('@/components/marketing/hero-scene').then((m) => m.HeroScene), {
  ssr: false,
  loading: () => <HeroSceneFallback className="h-full w-full opacity-70" />,
});

export function HeroSceneMount({ className }: { className?: string }) {
  return <HeroScene className={className} />;
}
