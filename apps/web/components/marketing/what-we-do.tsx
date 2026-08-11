import * as React from 'react';
import { Cog, EyeOff, Lock } from 'lucide-react';
import { Eyebrow, Section, SectionHeading, Lead } from '@/components/marketing/chrome';
import { Reveal, RevealGroup, RevealItem } from '@/components/marketing/motion';

/**
 * §2 — What we do. The third-party positioning: hrorg.in runs and secures internal HR operations for client
 * organizations, from onboarding to exit. Outcome framing only; the theme sentence (confidential by design)
 * carries here. No internal architecture vocabulary.
 */
const PILLARS = [
  {
    icon: Cog,
    title: 'We run it',
    body: 'We operate your HR day to day — onboarding, employment documents, attendance, communications, and exits — so your teams can focus on the business.',
  },
  {
    icon: Lock,
    title: 'We secure it',
    body: 'Access is controlled, sensitive details are encrypted, and everything is recorded — your operations and records stay protected throughout.',
  },
  {
    icon: EyeOff,
    title: 'We keep it confidential',
    body: 'Your people data stays yours. We secure how your HR runs — which is also why we don’t publish how it works inside.',
  },
];

export function WhatWeDo() {
  return (
    <Section id="product" className="border-t border-white/5">
      <div className="mx-auto max-w-2xl text-center">
        <Reveal>
          <Eyebrow>What we do</Eyebrow>
          <SectionHeading className="mt-4">
            We run and secure your <span className="text-gradient">Human Resource operations</span>.
          </SectionHeading>
          <Lead className="mx-auto mt-4 max-w-xl">
            hrorg.in is the partner you hand your internal HR to — from onboarding to exit, operated for you
            and kept confidential end to end.
          </Lead>
        </Reveal>
      </div>

      <RevealGroup className="mt-12 grid gap-4 md:grid-cols-3" gap={0.08}>
        {PILLARS.map((p) => {
          const Icon = p.icon;
          return (
            <RevealItem key={p.title}>
              <div className="h-full rounded-2xl border border-white/10 bg-white/[0.02] p-6 transition-colors hover:border-primary/40 hover:bg-white/[0.04]">
                <span className="flex size-11 items-center justify-center rounded-xl bg-primary/15 text-primary-bright ring-1 ring-inset ring-primary/25">
                  <Icon className="size-5" />
                </span>
                <div className="mt-4 text-lg font-semibold text-foreground">{p.title}</div>
                <p className="mt-1.5 text-sm leading-relaxed text-muted-foreground">{p.body}</p>
              </div>
            </RevealItem>
          );
        })}
      </RevealGroup>
    </Section>
  );
}
