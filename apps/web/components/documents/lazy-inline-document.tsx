'use client';

import dynamic from 'next/dynamic';
import { LoadingSkeleton } from '@/components/loading-skeleton';

/**
 * {@link InlineDocument} behind a lazy boundary. The read-and-sign renderer — the HTML→React document parser,
 * the injected document-body CSS, and (transitively) the signature capture UI — is pulled OUT of the offer /
 * agreement / offboarding-doc first-load bundles and only fetched at the point a document is actually shown.
 * Every one of those screens already renders {@link LoadingSkeleton} while its document data loads, so the
 * chunk-load fallback reuses the SAME skeleton — the swap is seamless, with no extra flash and nothing above
 * it to shift.
 */
export const LazyInlineDocument = dynamic(
  () => import('./inline-document').then((m) => m.InlineDocument),
  {
    ssr: false,
    loading: () => <LoadingSkeleton lines={10} />,
  },
);
