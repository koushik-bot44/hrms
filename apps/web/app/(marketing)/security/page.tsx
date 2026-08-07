import * as React from 'react';
import type { Metadata } from 'next';
import { PageIntro } from '@/components/marketing/chrome';
import { Security } from '@/components/marketing/security';
import { CtaStrip } from '@/components/marketing/cta-strip';

/**
 * /security — the security & confidentiality claims, given page weight, with the confidential-by-design theme
 * line front and centre. FULLY STATIC, provider-free, zero backend calls. Client-outcome framing only.
 */
const DESCRIPTION =
  'Your internal HR operations and records stay isolated, access-controlled, encrypted where sensitive, and ' +
  'audit-ready — secured end to end, and confidential by design.';

export const metadata: Metadata = {
  title: 'hrorg.in — Security',
  description: DESCRIPTION,
  alternates: { canonical: '/security' },
  openGraph: { title: 'hrorg.in — Security', description: DESCRIPTION },
};

export default function SecurityPage() {
  return (
    <>
      <PageIntro
        eyebrow="Security & confidentiality"
        title={
          <>
            Secured throughout, <span className="text-gradient">confidential by design</span>.
          </>
        }
        lead="We secure your internal HR operations end to end — which is also why we don't publish how it works inside. Your operations and data stay protected, and stay yours."
      />
      <Security />
      <CtaStrip title="Confidentiality your organization can rely on." />
    </>
  );
}
