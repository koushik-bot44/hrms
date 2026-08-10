import * as React from 'react';
import type { Metadata } from 'next';
import Link from 'next/link';
import { PageIntro } from '@/components/marketing/chrome';
import { SupportContent } from '@/components/marketing/support';
import { CtaStrip } from '@/components/marketing/cta-strip';

/**
 * /support — a practical FAQ for people already USING hrorg.in in their organization (employees, plus HR and
 * administrators), with support@hrorg.in as the help channel. FULLY STATIC, provider-free, zero backend calls.
 * Confidential register: answers solve real problems and never explain internal workflow or architecture; for
 * ACCESS enquiries this points to /contact (info@ stays Contact's only address).
 */
const DESCRIPTION =
  'Practical help for people already using hrorg.in — signing in, sign-in codes, signing and uploading ' +
  'documents, and where to turn when something isn’t working. For access enquiries, see Contact.';

export const metadata: Metadata = {
  title: 'hrorg.in — Support',
  description: DESCRIPTION,
  alternates: { canonical: '/support' },
  openGraph: { title: 'hrorg.in — Support', description: DESCRIPTION },
};

export default function SupportPage() {
  return (
    <>
      <PageIntro
        eyebrow="Support"
        title={
          <>
            Help for teams already <span className="text-gradient">using hrorg.in</span>
          </>
        }
        lead={
          <>
            Practical answers for the people who use hrorg.in day to day — signing in, filling in and signing
            documents, uploads, and who to ask when you’re stuck. Looking for access rather than help?{' '}
            <Link href="/contact" className="font-medium text-primary-bright underline-offset-4 hover:underline">
              Contact us
            </Link>
            .
          </>
        }
      />
      <SupportContent />
      <CtaStrip title="Need something beyond a quick fix? Let’s talk." />
    </>
  );
}
