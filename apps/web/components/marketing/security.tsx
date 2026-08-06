import * as React from 'react';
import {
  Building2,
  EyeOff,
  FileCheck2,
  History,
  Lock,
  Smartphone,
} from 'lucide-react';
import { BrowserFrame, Eyebrow, Section, SectionHeading, Lead } from '@/components/marketing/chrome';
import { Reveal, RevealGroup, RevealItem, Tilt } from '@/components/marketing/motion';
import { SecurityVignette } from '@/components/marketing/vignettes';

/**
 * §6 — security & trust. Claims EXACTLY the sanctioned set, no more (no SOC2/ISO/GDPR badges, no uptime/SLA).
 * Each maps to a real platform guarantee.
 */
const CLAIMS = [
  { icon: Building2, title: 'Per-company isolation', body: 'Every company is its own tenant; data never crosses the wall.' },
  { icon: Lock, title: 'Role-scoped access', body: 'Access is enforced by role on every endpoint, not just in the UI.' },
  { icon: EyeOff, title: 'Sensitive fields protected', body: 'PAN and Aadhaar are encrypted at rest, masked in the UI, and every reveal is audited.' },
  { icon: History, title: 'Append-only audit trail', body: 'Actions across the platform are recorded and cannot be edited away.' },
  { icon: Smartphone, title: 'OTP-verified access', body: 'Onboarding access is verified with a one-time code.' },
  { icon: FileCheck2, title: 'Tamper-evident PDFs', body: 'Signatures are rendered into stored PDFs kept on the record.' },
];

export function Security() {
  return (
    <Section id="security" className="border-t border-white/5">
      <div className="mx-auto max-w-2xl text-center">
        <Reveal>
          <Eyebrow>Security &amp; trust</Eyebrow>
          <SectionHeading className="mt-4">
            Built to be <span className="text-gradient">attributable</span> and contained.
          </SectionHeading>
          <Lead className="mx-auto mt-4 max-w-xl">
            Governance isn&apos;t a feature bolted on — it&apos;s how the platform is built: scoped, encrypted,
            and audited by default.
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
            <BrowserFrame url="acme.company.example/record">
              <SecurityVignette />
            </BrowserFrame>
          </Tilt>
        </Reveal>
      </div>
    </Section>
  );
}
