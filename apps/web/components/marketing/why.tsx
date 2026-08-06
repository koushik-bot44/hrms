import * as React from 'react';
import { FileSignature, Layers, ScrollText, ShieldCheck } from 'lucide-react';
import { Eyebrow, Section, SectionHeading } from '@/components/marketing/chrome';
import { Reveal, RevealGroup, RevealItem } from '@/components/marketing/motion';

/** §7 — why IHRMS. Four benefit cards, each a truthful consequence of the platform. */
const BENEFITS = [
  {
    icon: Layers,
    title: 'One system for the whole lifecycle',
    body: 'From invite to relieving letter, every stage lives in the same place — no handoffs between disconnected tools.',
  },
  {
    icon: ScrollText,
    title: 'Every action attributable',
    body: 'An append-only audit trail means you can always answer who did what, and when.',
  },
  {
    icon: FileSignature,
    title: 'Legal documents without paper',
    body: 'Offers and agreements are generated, signed in place, and stored as PDFs on the record.',
  },
  {
    icon: ShieldCheck,
    title: 'Role clarity across companies',
    body: 'Scoped roles and per-company isolation keep responsibilities — and data — exactly where they belong.',
  },
];

export function WhyIhrms() {
  return (
    <Section id="why" className="border-t border-white/5">
      <div className="mx-auto max-w-2xl text-center">
        <Reveal>
          <Eyebrow>Why IHRMS</Eyebrow>
          <SectionHeading className="mt-4">
            The whole lifecycle, <span className="text-gradient">governed by default</span>.
          </SectionHeading>
        </Reveal>
      </div>

      <RevealGroup className="mt-12 grid gap-4 sm:grid-cols-2" gap={0.08}>
        {BENEFITS.map((b) => {
          const Icon = b.icon;
          return (
            <RevealItem key={b.title}>
              <div className="flex h-full gap-4 rounded-2xl border border-white/10 bg-white/[0.02] p-6 transition-colors hover:border-primary/40 hover:bg-white/[0.04]">
                <span className="flex size-11 shrink-0 items-center justify-center rounded-xl bg-primary/15 text-primary-bright ring-1 ring-inset ring-primary/25">
                  <Icon className="size-5" />
                </span>
                <div>
                  <div className="text-lg font-semibold text-foreground">{b.title}</div>
                  <p className="mt-1.5 text-sm leading-relaxed text-muted-foreground">{b.body}</p>
                </div>
              </div>
            </RevealItem>
          );
        })}
      </RevealGroup>
    </Section>
  );
}
