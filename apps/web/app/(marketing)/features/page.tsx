import * as React from 'react';
import type { Metadata } from 'next';
import { PageIntro } from '@/components/marketing/chrome';
import { Features } from '@/components/marketing/features';
import { CtaStrip } from '@/components/marketing/cta-strip';

/**
 * /features — the five capability groups in full (the alternating vignette layout). FULLY STATIC, provider-
 * free, zero backend calls. Outcome copy only; no internal architecture.
 */
const DESCRIPTION =
  'What hrorg.in runs for you — digital onboarding with e-signed documents, attendance and leave with ' +
  'analytics and payroll-cycle reporting, internal communications, document and letter services, and ' +
  'structured exits.';

export const metadata: Metadata = {
  title: 'hrorg.in — Features',
  description: DESCRIPTION,
  alternates: { canonical: '/features' },
  openGraph: { title: 'hrorg.in — Features', description: DESCRIPTION },
};

export default function FeaturesPage() {
  return (
    <>
      <PageIntro
        eyebrow="Capabilities"
        title={
          <>
            Everything your HR needs, <span className="text-gradient">run for you</span>.
          </>
        }
        lead="From onboarding to exit, hrorg.in operates the capabilities your teams rely on — with outcomes you can point to, and none of the busywork."
      />
      <Features />
      <CtaStrip title="See a capability that fits? Let's talk." />
    </>
  );
}
