import * as React from 'react';
import Link from 'next/link';
import { Eyebrow, SectionHeading, Lead } from '@/components/marketing/chrome';
import { Reveal } from '@/components/marketing/motion';
import { ContactForm } from '@/components/marketing/contact-form';

/**
 * /contact treatment. Interested organizations request access via the form (submissions are emailed, never
 * stored); info@hrorg.in remains as a mailto for people who prefer their own client. Access is by arrangement —
 * no self-serve sign-up, no fake phone/socials. This is where "Not subscribed yet? Contact us" lands from every
 * page. The section stays server-rendered; only <ContactForm> hydrates.
 */
export function Contact() {
  return (
    <section className="relative overflow-hidden px-5 pb-24 pt-28 sm:px-8 sm:pt-36">
      <div aria-hidden className="pointer-events-none absolute inset-0 m-aurora opacity-60" />
      <div aria-hidden className="pointer-events-none absolute inset-0 m-grid" />

      <Reveal className="relative mx-auto max-w-2xl">
        <div className="m-glass overflow-hidden rounded-3xl p-7 shadow-2xl shadow-black/40 sm:p-10">
          <div className="text-center">
            <Eyebrow>Contact</Eyebrow>
            <SectionHeading as="h1" className="mt-4">
              Bring your internal HR to <span className="text-gradient">hrorg.in</span>
            </SectionHeading>
            <Lead className="mx-auto mt-5 max-w-xl">
              Access to hrorg.in is by arrangement. Tell us about your organization and how your HR runs today —
              we&apos;ll take it from there, run securely and kept confidential.
            </Lead>
          </div>

          <div className="mt-8">
            <ContactForm />
          </div>

          <p className="mt-6 text-center text-sm text-muted-foreground">
            Prefer your own client? Email{' '}
            <a href="mailto:info@hrorg.in" className="font-medium text-primary-bright hover:underline">
              info@hrorg.in
            </a>
            .
          </p>
        </div>

        <p className="mt-8 text-center text-sm text-muted-foreground">
          Already have access?{' '}
          <Link href="/login" className="font-medium text-primary-bright underline-offset-4 hover:underline">
            Sign in
          </Link>
        </p>
      </Reveal>
    </section>
  );
}
