import * as React from 'react';
import { MarketingNav } from '@/components/marketing/marketing-nav';
import { Hero } from '@/components/marketing/hero';
import { ProblemAnswer } from '@/components/marketing/problem';
import { Lifecycle } from '@/components/marketing/lifecycle';
import { Features } from '@/components/marketing/features';
import { Roles } from '@/components/marketing/roles';
import { Security } from '@/components/marketing/security';
import { WhyIhrms } from '@/components/marketing/why';
import { FooterCta } from '@/components/marketing/footer-cta';

/**
 * The public marketing one-pager at `/`. FULLY STATIC — a server component assembling server-rendered sections
 * (real copy is in the SSR HTML for SEO); the only client islands are scroll-reveals, pointer tilts, the nav
 * scroll state, and the lazy hero scene. It makes ZERO backend calls and renders identically for signed-in and
 * signed-out visitors (it is isolated from the app's providers by its own `(marketing)` root layout). Every
 * claim maps to a shipped capability; all UI vignettes use obviously-generic placeholder data.
 */
export default function MarketingPage() {
  return (
    <div className="relative min-h-dvh scroll-smooth">
      <MarketingNav />
      <main>
        <Hero />
        <ProblemAnswer />
        <Lifecycle />
        <Features />
        <Roles />
        <Security />
        <WhyIhrms />
      </main>
      <FooterCta />
    </div>
  );
}
