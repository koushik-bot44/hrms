import * as React from 'react';
import {
  EyeOff,
  FileCheck2,
  History,
  Lock,
  ShieldCheck,
  UserCheck,
} from 'lucide-react';
import { BrowserFrame, Eyebrow, Section, SectionHeading, Lead } from '@/components/marketing/chrome';
import { Reveal, RevealGroup, RevealItem, Tilt } from '@/components/marketing/motion';
import { SecurityVignette } from '@/components/marketing/vignettes';

/**
 * §4 — security & confidentiality, framed as the CLIENT's outcome (their operations and records are isolated,
 * access-controlled, encrypted where sensitive, and fully audited) — no architecture vocabulary, plus the
 * confidential-by-design line. Truthful guarantees only.
 */
const CLAIMS = [
  { icon: ShieldCheck, title: 'Isolated operations', body: 'Your HR operations and records stay walled off — yours alone.' },
  { icon: Lock, title: 'Access-controlled', body: 'Only the right people see the right information, and no more.' },
  { icon: EyeOff, title: 'Encrypted where sensitive', body: 'Sensitive details are encrypted at rest and shown masked.' },
  { icon: History, title: 'Audit-ready records', body: 'Every action is recorded, so your records hold up later.' },
  { icon: UserCheck, title: 'Confirmed access', body: 'Every sign-in is confirmed before anyone gets in.' },
  { icon: FileCheck2, title: 'Documents kept intact', body: 'Signed documents are stored as tamper-evident PDFs.' },
];

export function Security() {
  return (
    <Section id="security" className="border-t border-white/5">
      <div className="mx-auto max-w-2xl text-center">
        <Reveal>
          <Eyebrow>Security &amp; confidentiality</Eyebrow>
          <SectionHeading className="mt-4">
            Secured throughout, <span className="text-gradient">confidential by design</span>.
          </SectionHeading>
          <Lead className="mx-auto mt-4 max-w-xl">
            We secure your internal HR operations end to end — which is also why we don&apos;t publish how it
            works inside. Your operations and data stay protected, and stay yours.
          </Lead>
        </Reveal>
      </div>

      <div className="mt-12 grid items-center gap-10 lg:grid-cols-[1.1fr_0.9fr]">
        <RevealGroup className="grid gap-3 sm:grid-cols-2" gap={0.06}>
          {CLAIMS.map((c) => {
            const Icon = c.icon;
            return (
              <RevealItem key={c.title}>
                <div className="flex h-full gap-3 rounded-2xl border border-white/10 bg-white/[0.02] p-4">
                  <span className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-primary/15 text-primary-bright ring-1 ring-inset ring-primary/25">
                    <Icon className="size-[1.125rem]" />
                  </span>
                  <div>
                    <div className="text-sm font-semibold text-foreground">{c.title}</div>
                    <p className="mt-1 text-[13px] leading-relaxed text-muted-foreground">{c.body}</p>
                  </div>
                </div>
              </RevealItem>
            );
          })}
        </RevealGroup>

        <Reveal className="[perspective:1200px]" delay={0.1}>
          <Tilt max={6}>
            <BrowserFrame url="acme.company.example/records">
              <SecurityVignette />
            </BrowserFrame>
          </Tilt>
        </Reveal>
      </div>
    </Section>
  );
}
