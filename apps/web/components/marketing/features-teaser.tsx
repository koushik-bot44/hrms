import * as React from 'react';
import Link from 'next/link';
import { ArrowRight, CalendarClock, FileSignature, LogOut, Mail, ScrollText } from 'lucide-react';
import { Eyebrow, Section, SectionHeading } from '@/components/marketing/chrome';
import { Reveal, RevealGroup, RevealItem } from '@/components/marketing/motion';

/**
 * Home-page features teaser — the five capability headlines as compact cards (no full vignette treatment),
 * each linking through to /features. Outcome copy only.
 */
const CAPS = [
  { icon: FileSignature, label: 'Onboarding & documents', line: 'Digital onboarding with e-signed employment documents.' },
  { icon: CalendarClock, label: 'Attendance & leave', line: 'Analytics and payroll-cycle reporting, done for you.' },
  { icon: Mail, label: 'Internal communications', line: 'A private communications space for your organization.' },
  { icon: ScrollText, label: 'Documents & letters', line: 'Payslips, letters, and documents issued on request.' },
  { icon: LogOut, label: 'Structured exits', line: 'Orderly offboarding, clearance, and closing letters.' },
];

export function FeaturesTeaser() {
  return (
    <Section className="border-t border-white/5 bg-white/[0.015]">
      <div className="flex flex-col items-start justify-between gap-4 sm:flex-row sm:items-end">
        <Reveal>
          <Eyebrow>Capabilities</Eyebrow>
          <SectionHeading className="mt-4 max-w-xl">
            Everything your HR needs, <span className="text-gradient">run for you</span>.
          </SectionHeading>
        </Reveal>
        <Reveal delay={0.1}>
          <Link
            href="/features"
            className="inline-flex min-h-11 items-center gap-2 rounded-full border border-white/15 bg-white/5 px-4 py-2 text-sm font-semibold text-foreground transition-colors hover:bg-white/10"
          >
            See all capabilities
            <ArrowRight className="size-4" />
          </Link>
        </Reveal>
      </div>

      <RevealGroup className="mt-10 grid gap-3 sm:grid-cols-2 lg:grid-cols-3" gap={0.06}>
        {CAPS.map((c) => {
          const Icon = c.icon;
          return (
            <RevealItem key={c.label}>
              <Link
                href="/features"
                className="group flex h-full flex-col rounded-2xl border border-white/10 bg-white/[0.02] p-5 transition-colors hover:border-primary/40 hover:bg-white/[0.04]"
              >
                <span className="flex size-10 items-center justify-center rounded-xl bg-primary/15 text-primary-bright ring-1 ring-inset ring-primary/25">
                  <Icon className="size-5" />
                </span>
                <div className="mt-3 font-semibold text-foreground">{c.label}</div>
                <p className="mt-1 text-sm text-muted-foreground">{c.line}</p>
                <span className="mt-4 inline-flex items-center gap-1 text-sm font-medium text-primary-bright">
                  Learn more
                  <ArrowRight className="size-3.5 transition-transform group-hover:translate-x-0.5" />
                </span>
              </Link>
            </RevealItem>
          );
        })}
      </RevealGroup>
    </Section>
  );
}
