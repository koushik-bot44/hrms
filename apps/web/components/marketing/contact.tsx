import * as React from 'react';
import Link from 'next/link';
import { Mail } from 'lucide-react';
import { Eyebrow, SectionHeading, Lead } from '@/components/marketing/chrome';
import { Reveal } from '@/components/marketing/motion';

/**
 * /contact treatment. Invites organizations to get in touch; info@hrorg.in is the single prominent call to
 * action (a mailto link). Access is by arrangement — no self-serve sign-up, no form, no fake phone/socials.
 * This is where "Not subscribed yet? Contact us" lands from every page.
 */
export function Contact() {
  return (
    <section className="relative overflow-hidden px-5 pb-24 pt-28 sm:px-8 sm:pt-36">
      <div aria-hidden className="pointer-events-none absolute inset-0 m-aurora opacity-60" />
      <div aria-hidden className="pointer-events-none absolute inset-0 m-grid" />

      <Reveal className="relative mx-auto max-w-3xl">
        <div className="m-glass overflow-hidden rounded-3xl p-8 text-center shadow-2xl shadow-black/40 sm:p-14">
          <Eyebrow>Contact</Eyebrow>
          <SectionHeading as="h1" className="mt-4">
            Bring your internal HR to <span className="text-gradient">hrorg.in</span>
          </SectionHeading>
          <Lead className="mx-auto mt-5 max-w-xl">
            Access to hrorg.in is by arrangement. Tell us about your organization and how your HR runs today —
            we&apos;ll take it from there, run securely and kept confidential.
          </Lead>

          <a
            href="mailto:info@hrorg.in"
            className="mt-9 inline-flex items-center gap-3 rounded-full bg-primary px-7 py-4 text-lg font-semibold text-primary-foreground shadow-lg shadow-primary/30 transition-transform hover:-translate-y-0.5"
          >
            <Mail className="size-5" />
            info@hrorg.in
          </a>
          <p className="mt-4 text-sm text-muted-foreground">
            Write to us and a member of our team will get back to you to arrange access.
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
