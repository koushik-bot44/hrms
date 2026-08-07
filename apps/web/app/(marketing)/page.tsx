import * as React from 'react';
import type { Metadata } from 'next';
import { Hero } from '@/components/marketing/hero';
import { WhatWeDo } from '@/components/marketing/what-we-do';
import { FeaturesTeaser } from '@/components/marketing/features-teaser';
import { WhyHrorg } from '@/components/marketing/why';
import { FinalCta } from '@/components/marketing/final-cta';

/**
 * Home (`/`). FULLY STATIC and provider-free (isolated by the `(marketing)` root layout; the marketing-scoped
 * middleware redirects a signed-in visitor before this is served). Zero backend calls. Assembles the hero, the
 * third-party positioning, a condensed capability teaser that links to /features, the outcome cards, and the
 * closing CTA. Nav + footer come from the shared layout. Confidential register throughout; generic vignettes.
 */
export const metadata: Metadata = {
  alternates: { canonical: '/' },
};

export default function HomePage() {
  return (
    <>
      <Hero />
      <WhatWeDo />
      <FeaturesTeaser />
      <WhyHrorg />
      <FinalCta />
    </>
  );
}
