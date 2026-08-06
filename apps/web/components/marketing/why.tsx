import * as React from 'react';
import { FileSignature, Layers, Lock, ScrollText } from 'lucide-react';
import { Eyebrow, Section, SectionHeading } from '@/components/marketing/chrome';
import { Reveal, RevealGroup, RevealItem } from '@/components/marketing/motion';

/** §5 — why hrorg.in. Outcome cards in a corporate register; each a truthful consequence of the service. */
const BENEFITS = [
  {
    icon: Layers,
    title: 'One partner for the whole employee journey',
    body: 'Onboarding to exit, operated and secured in one place — no scattered tools or handoffs.',
  },
  {
    icon: ScrollText,
    title: 'Audit-ready records',
    body: 'Every action is recorded, so you can always show what happened, and when.',
  },
  {
    icon: FileSignature,
    title: 'Digitally executed employment documentation',
    body: 'Employment documents generated, e-signed, and stored as finished PDFs — no paper.',
  },
  {
    icon: Lock,
    title: 'Confidentiality by design',
    body: 'Your operations and data stay private, protected, and yours.',
  },
];

export function WhyHrorg() {
  return (
    <Section id="why" className="border-t border-white/5">
      <div className="mx-auto max-w-2xl text-center">
        <Reveal>
          <Eyebrow>Why hrorg.in</Eyebrow>
          <SectionHeading className="mt-4">
            Outcomes your organization can <span className="text-gradient">stand behind</span>.
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
