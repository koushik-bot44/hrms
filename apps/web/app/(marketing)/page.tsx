import * as React from 'react';
import { MarketingNav } from '@/components/marketing/marketing-nav';
import { Hero } from '@/components/marketing/hero';
import { WhatWeDo } from '@/components/marketing/what-we-do';
import { Features } from '@/components/marketing/features';
import { Security } from '@/components/marketing/security';
import { WhyHrorg } from '@/components/marketing/why';
import { Contact } from '@/components/marketing/contact';
import { FooterCta } from '@/components/marketing/footer-cta';

/**
 * The public marketing one-pager at `/`. FULLY STATIC — a server component assembling server-rendered sections
 * (real copy is in the SSR HTML for SEO); the only client islands are scroll-reveals, pointer tilts, the nav,
 * and the lazy hero scene. It makes ZERO backend calls and renders identically for signed-in and signed-out
 * visitors (isolated from the app's providers by its own `(marketing)` root layout; the `/`-scoped middleware
 * redirects a signed-in visitor before this page is served).
 *
 * Positioning: hrorg.in is a third party that RUNS and SECURES client organizations' internal HR operations.
 * The copy sells outcomes and capabilities, never the internal architecture — confidential by design. Product
 * visuals are stylized in-code vignettes with obviously-generic placeholder data.
 */
export default function MarketingPage() {
  return (
    <div className="relative min-h-dvh scroll-smooth">
      <MarketingNav />
      <main>
        <Hero />
        <WhatWeDo />
        <Features />
        <Security />
        <WhyHrorg />
        <Contact />
      </main>
      <FooterCta />
    </div>
  );
}
