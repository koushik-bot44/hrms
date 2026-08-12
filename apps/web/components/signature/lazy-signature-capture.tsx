'use client';

import dynamic from 'next/dynamic';
import { Skeleton } from '@/components/ui/skeleton';

/**
 * {@link SignatureCapture} behind a lazy boundary. The heavy capture UI — four modes, canvas pixel cleaning,
 * on-demand next/font rasterization — is pulled OUT of the read-and-sign + onboarding first-load bundles and
 * only fetched when the signature dialog actually opens. The self-hosted signature faces are declared on
 * <html> by the (app) layout (app/fonts.ts), wholly independent of this chunk, so the boundary never touches
 * font loading. The placeholder mirrors the real footprint (four mode tabs, the 520×170 canvas, the adopt
 * row) so the open dialog doesn't jump when the chunk lands.
 */
export const LazySignatureCapture = dynamic(
  () => import('./signature-capture').then((m) => m.SignatureCapture),
  {
    ssr: false,
    loading: () => (
      <div className="space-y-3" aria-hidden>
        <div className="flex flex-wrap gap-2">
          {[0, 1, 2, 3].map((i) => (
            <Skeleton key={i} className="h-9 w-28 rounded-md" />
          ))}
        </div>
        <Skeleton className="aspect-[52/17] w-full rounded-md" />
        <Skeleton className="h-8 w-40 rounded-md" />
      </div>
    ),
  },
);
