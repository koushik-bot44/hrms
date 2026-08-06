import * as React from 'react';
import { Mail } from 'lucide-react';
import { Eyebrow, Section, SectionHeading, Lead } from '@/components/marketing/chrome';
import { Reveal } from '@/components/marketing/motion';

/**
 * §6 — Contact. Invites organizations to get in touch; the info@hrorg.in address is the single, prominent
 * call to action (a mailto link). No form, no fake phone/address/socials.
 */
export function Contact() {
  return (
    <Section id="contact" className="border-t border-white/5">
      <Reveal>
        <div className="m-glass relative overflow-hidden rounded-3xl p-8 text-center shadow-2xl shadow-black/40 sm:p-12">
          <div aria-hidden className="pointer-events-none absolute inset-0 m-aurora opacity-40" />
          <div className="relative mx-auto max-w-2xl">
            <Eyebrow>Contact</Eyebrow>
            <SectionHeading className="mt-4">
              Bring your internal HR to <span className="text-gradient">hrorg.in</span>.
            </SectionHeading>
            <Lead className="mx-auto mt-4 max-w-xl">
              Tell us about your organization and how your HR runs today. We&apos;ll take it from there — run
              securely, kept confidential.
            </Lead>

            <a
              href="mailto:info@hrorg.in"
              className="mt-8 inline-flex items-center gap-3 rounded-full bg-primary px-6 py-3.5 text-base font-semibold text-primary-foreground shadow-lg shadow-primary/30 transition-transform hover:-translate-y-0.5"
            >
              <Mail className="size-5" />
              info@hrorg.in
            </a>
            <p className="mt-4 text-sm text-muted-foreground">
              Write to us and a member of our team will get back to you.
            </p>
          </div>
        </div>
      </Reveal>
    </Section>
  );
}
