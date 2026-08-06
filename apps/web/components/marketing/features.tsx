import * as React from 'react';
import { Check } from 'lucide-react';
import { cn } from '@/lib/utils';
import { BrowserFrame, Eyebrow, Section, SectionHeading } from '@/components/marketing/chrome';
import { Reveal, Tilt } from '@/components/marketing/motion';
import {
  AttendanceVignette,
  ClearanceVignette,
  InlineDocVignette,
  MailVignette,
  RequestVignette,
} from '@/components/marketing/vignettes';

/**
 * §3 — capability groups, alternating layout. Each: an eyebrow + headline, outcome-focused points (WHAT the
 * client gets, never HOW the machinery works inside), and a browser-framed in-code vignette. No internal
 * workflow vocabulary.
 */
type Group = {
  eyebrow: string;
  title: string;
  points: string[];
  url: string;
  vignette: React.ReactNode;
};

const GROUPS: Group[] = [
  {
    eyebrow: 'Onboarding & documents',
    title: 'Digital onboarding with e-signed employment documents',
    points: [
      'New hires brought onboard digitally, start to finish',
      'Employment documents e-signed in place and issued as finished PDFs',
      'Signatures captured however people prefer — draw, type, or upload',
      'Completed paperwork kept on file, ready whenever you need it',
    ],
    url: 'acme.company.example/onboarding',
    vignette: <InlineDocVignette />,
  },
  {
    eyebrow: 'Attendance & leave',
    title: 'Attendance and leave, with analytics and payroll-cycle reporting',
    points: [
      'Clock-in and clock-out with breaks — overnight shifts handled correctly',
      'Adherence and absence analytics at a glance',
      'Reporting by day, month, payroll cycle, or custom range',
      'Team views and CSV export; leave managed through to a decision',
    ],
    url: 'acme.company.example/attendance',
    vignette: <AttendanceVignette />,
  },
  {
    eyebrow: 'Internal communications',
    title: 'A private communications space for your organization',
    points: [
      'Conversations with attachments, stars, and labels',
      'Fast search and filters to find anything quickly',
      'On your organization’s own domain',
      'Private to your organization',
    ],
    url: 'mail.company.example',
    vignette: <MailVignette />,
  },
  {
    eyebrow: 'Documents & letters',
    title: 'Document and letter services, on request',
    points: [
      'Employees request payslips, letters, and documents in a few taps',
      'Requests reach the right team and are actioned',
      'Status tracked end to end, for everyone involved',
      'Letters issued as finished, audit-ready PDFs',
    ],
    url: 'acme.company.example/requests',
    vignette: <RequestVignette />,
  },
  {
    eyebrow: 'Exits',
    title: 'Structured exits, handled with care',
    points: [
      'Orderly offboarding managed end to end',
      'Settlement and separation documentation prepared per case',
      'An internal clearance checklist across teams',
      'Relieving and experience letters generated with a preview',
    ],
    url: 'acme.company.example/exits',
    vignette: <ClearanceVignette />,
  },
];

function FeatureRow({ group, index }: { group: Group; index: number }) {
  const flip = index % 2 === 1;
  return (
    <div className="grid items-center gap-8 md:grid-cols-2 md:gap-12">
      {/* copy */}
      <Reveal className={cn(flip && 'md:order-2')}>
        <Eyebrow>{group.eyebrow}</Eyebrow>
        <SectionHeading as="h3" className="mt-3 text-2xl sm:text-3xl">
          {group.title}
        </SectionHeading>
        <ul className="mt-5 space-y-2.5">
          {group.points.map((p) => (
            <li key={p} className="flex items-start gap-3 text-sm text-muted-foreground">
              <span className="mt-0.5 flex size-5 shrink-0 items-center justify-center rounded-full bg-primary/15 text-primary-bright ring-1 ring-inset ring-primary/30">
                <Check className="size-3" />
              </span>
              <span className="text-pretty">{p}</span>
            </li>
          ))}
        </ul>
      </Reveal>

      {/* vignette */}
      <Reveal className={cn('[perspective:1200px]', flip && 'md:order-1')} delay={0.1}>
        <Tilt max={6}>
          <BrowserFrame url={group.url}>{group.vignette}</BrowserFrame>
        </Tilt>
      </Reveal>
    </div>
  );
}

export function Features() {
  return (
    <Section id="features" className="border-t border-white/5 bg-white/[0.015]">
      <div className="mx-auto max-w-2xl text-center">
        <Reveal>
          <Eyebrow>Capabilities</Eyebrow>
          <SectionHeading className="mt-4">
            Everything your HR needs, <span className="text-gradient">run for you</span>.
          </SectionHeading>
        </Reveal>
      </div>

      <div className="mt-16 space-y-20 md:space-y-28">
        {GROUPS.map((group, i) => (
          <FeatureRow key={group.eyebrow} group={group} index={i} />
        ))}
      </div>
    </Section>
  );
}
