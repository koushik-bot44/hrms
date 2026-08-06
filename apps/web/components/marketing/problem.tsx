import * as React from 'react';
import { Check, X } from 'lucide-react';
import { Eyebrow, Section, SectionHeading, Lead } from '@/components/marketing/chrome';
import { Reveal, RevealGroup, RevealItem } from '@/components/marketing/motion';

/**
 * §2 — the problem → the answer. A two-column contrast strip: scattered HR reality vs. one governed system.
 * Truthful framing only (no invented stats).
 */
const BEFORE = [
  'Paper onboarding packs and PDFs over email',
  'Signatures chased across inboxes',
  'Attendance tracked in spreadsheets',
  'Company mail scattered across tools',
  'Records no one can verify after the fact',
] as const;

const AFTER = [
  'Guided onboarding with documents signed in place',
  'E-signatures captured and stored on the record',
  'Attendance, breaks and leave in one system',
  'Internal mail on your own company domain',
  'Every action tracked in an append-only audit trail',
] as const;

export function ProblemAnswer() {
  return (
    <Section id="product" className="border-t border-white/5">
      <div className="mx-auto max-w-2xl text-center">
        <Reveal>
          <Eyebrow>Why it matters</Eyebrow>
          <SectionHeading className="mt-4">
            HR shouldn&apos;t live in inboxes and <span className="text-gradient">spreadsheets</span>.
          </SectionHeading>
          <Lead className="mx-auto mt-4 max-w-xl">
            The everyday reality is scattered and hard to verify. IHRMS replaces it with one system where every
            step is tracked, signed, and audited.
          </Lead>
        </Reveal>
      </div>

      <div className="mt-12 grid gap-5 md:grid-cols-2">
        <Reveal>
          <div className="h-full rounded-2xl border border-white/10 bg-white/[0.02] p-6">
            <div className="mb-4 text-sm font-semibold uppercase tracking-wide text-muted-foreground">
              The scattered way
            </div>
            <ul className="space-y-3">
              {BEFORE.map((b) => (
                <li key={b} className="flex items-start gap-3 text-sm text-muted-foreground">
                  <span className="mt-0.5 flex size-5 shrink-0 items-center justify-center rounded-full bg-white/5 text-muted-foreground ring-1 ring-inset ring-white/10">
                    <X className="size-3" />
                  </span>
                  <span className="line-through decoration-white/20">{b}</span>
                </li>
              ))}
            </ul>
          </div>
        </Reveal>

        <Reveal delay={0.1}>
          <div className="relative h-full overflow-hidden rounded-2xl border border-primary/30 bg-primary/[0.06] p-6">
            <div aria-hidden className="pointer-events-none absolute -right-16 -top-16 size-48 rounded-full bg-primary/20 blur-3xl" />
            <div className="relative mb-4 text-sm font-semibold uppercase tracking-wide text-primary-bright">
              The governed way
            </div>
            <RevealGroup className="relative space-y-3">
              {AFTER.map((a) => (
                <RevealItem key={a}>
                  <div className="flex items-start gap-3 text-sm text-foreground">
                    <span className="mt-0.5 flex size-5 shrink-0 items-center justify-center rounded-full bg-primary/20 text-primary-bright ring-1 ring-inset ring-primary/40">
                      <Check className="size-3" />
                    </span>
                    <span>{a}</span>
                  </div>
                </RevealItem>
              ))}
            </RevealGroup>
          </div>
        </Reveal>
      </div>
    </Section>
  );
}
