import * as React from 'react';
import {
  BadgeCheck,
  CalendarClock,
  ClipboardList,
  FileCheck2,
  FileSignature,
  Lock,
  LogOut,
  Mail,
  ScrollText,
  UserPlus,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import { Eyebrow, Section, SectionHeading, Lead } from '@/components/marketing/chrome';
import { Reveal } from '@/components/marketing/motion';
import { LifecycleRail } from '@/components/marketing/lifecycle-rail';

/**
 * §3 — THE LIFECYCLE, the centerpiece. A scroll-driven vertical journey: each real step of the platform, one
 * line + a micro-vignette, connected by a progress rail that fills on scroll. Steps alternate sides on desktop
 * and stack on mobile. Every step maps to a shipped capability.
 */
type Step = {
  icon: React.ComponentType<{ className?: string }>;
  title: string;
  desc: string;
  tags: string[];
};

const STEPS: Step[] = [
  {
    icon: UserPlus,
    title: 'Invite',
    desc: 'HR invites a new hire with their basic details — the lifecycle begins with a single action.',
    tags: ['HR-initiated', 'OTP access'],
  },
  {
    icon: FileSignature,
    title: 'Offer letter',
    desc: 'The company-issued offer is read, signed, and accepted in place. Onboarding forms stay locked until the offer is accepted.',
    tags: ['Read → sign → accept', 'Gated'],
  },
  {
    icon: ClipboardList,
    title: 'Onboarding forms & uploads',
    desc: 'The employee completes guided forms and uploads identity and background documents.',
    tags: ['Guided forms', 'Document uploads'],
  },
  {
    icon: FileCheck2,
    title: 'HR verification',
    desc: 'HR reviews each submission and can send items back with a note — a clean revision loop until everything checks out.',
    tags: ['Review', 'Revision loop'],
  },
  {
    icon: BadgeCheck,
    title: 'Approval',
    desc: 'On approval a unique employee ID is minted and the manager is notified — the record becomes official.',
    tags: ['Employee ID minted', 'Manager notified'],
  },
  {
    icon: CalendarClock,
    title: 'Working life',
    desc: 'Day to day: attendance and breaks, leave applications, internal mail, and requests — all in one place.',
    tags: ['Attendance & leave', 'Mail & requests'],
  },
  {
    icon: LogOut,
    title: 'Offboarding',
    desc: 'Exits require leadership approval, then run case-specific settlement and separation documents plus an internal clearance checklist.',
    tags: ['Leadership-approved', 'Clearance'],
  },
  {
    icon: ScrollText,
    title: 'Relieving & experience letters',
    desc: 'Relieving and experience letters are generated with a preview before they are issued.',
    tags: ['Generated', 'Preview'],
  },
  {
    icon: Lock,
    title: 'Controlled deactivation',
    desc: 'The account is deactivated only when HR says so — a deliberate, final step, never automatic.',
    tags: ['HR-controlled', 'Deliberate'],
  },
];

function StepRow({ step, index }: { step: Step; index: number }) {
  const left = index % 2 === 0;
  const Icon = step.icon;
  return (
    <div className="relative md:grid md:grid-cols-2 md:gap-12">
      {/* node on the rail */}
      <div className="absolute left-4 top-0.5 z-10 -translate-x-1/2 md:left-1/2">
        <span className="flex size-8 items-center justify-center rounded-full bg-primary text-xs font-semibold text-primary-foreground shadow-lg shadow-primary/40 ring-4 ring-[hsl(var(--background))]">
          {index + 1}
        </span>
      </div>

      <div
        className={cn(
          'pl-12 md:pl-0',
          left ? 'md:col-start-1 md:pr-14' : 'md:col-start-2 md:pl-14',
        )}
      >
        <Reveal>
          <div className="rounded-2xl border border-white/10 bg-white/[0.02] p-5 transition-colors hover:border-primary/40 hover:bg-white/[0.04]">
            <div className="flex items-center gap-3">
              <span className="flex size-9 items-center justify-center rounded-lg bg-primary/15 text-primary-bright ring-1 ring-inset ring-primary/25">
                <Icon className="size-5" />
              </span>
              <h3 className="text-lg font-semibold text-foreground">{step.title}</h3>
            </div>
            <p className="mt-3 text-sm leading-relaxed text-muted-foreground">{step.desc}</p>
            <div className="mt-3 flex flex-wrap gap-1.5">
              {step.tags.map((t) => (
                <span
                  key={t}
                  className="rounded-full bg-white/5 px-2 py-0.5 text-[11px] text-muted-foreground ring-1 ring-inset ring-white/10"
                >
                  {t}
                </span>
              ))}
            </div>
          </div>
        </Reveal>
      </div>
    </div>
  );
}

export function Lifecycle() {
  return (
    <Section id="workflow" className="border-t border-white/5">
      <div className="mx-auto max-w-2xl text-center">
        <Reveal>
          <Eyebrow>The journey</Eyebrow>
          <SectionHeading className="mt-4">
            One continuous path, from <span className="text-gradient">invite to exit</span>.
          </SectionHeading>
          <Lead className="mx-auto mt-4 max-w-xl">
            Every stage of employment is a step in the same governed flow — nothing falls through the cracks
            between tools.
          </Lead>
        </Reveal>
      </div>

      <div className="relative mx-auto mt-14 max-w-4xl">
        <LifecycleRail />
        <div className="space-y-8 md:space-y-12">
          {STEPS.map((step, i) => (
            <StepRow key={step.title} step={step} index={i} />
          ))}
        </div>
      </div>
    </Section>
  );
}
