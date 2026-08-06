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
 * §4 — feature groups, alternating layout. Each: an eyebrow + headline, 3–5 REAL capability points, and a
 * browser-framed in-code vignette. Copy is drawn strictly from the shipped inventory — no extrapolation.
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
    eyebrow: 'Documents & e-sign',
    title: 'Legal documents, generated and signed in place',
    points: [
      'Offer letters and agreements generated from your templates, with the company name and terms filled in',
      'Employees fill the blanks inside the document and sign right at the line',
      'Four ways to sign — draw, type a style, upload, or upload and clean up',
      'Every submission becomes a stored PDF on the employee record',
    ],
    url: 'acme.company.example/offer',
    vignette: <InlineDocVignette />,
  },
  {
    eyebrow: 'Attendance & leave',
    title: 'Time tracked correctly, analytics that hold up',
    points: [
      'Clock in and out with breaks — overnight shifts handled correctly',
      'Adherence and unapproved-absence analytics',
      'Today, Month, Payroll-cycle and Custom date ranges',
      'Team composition views and CSV export',
      'Leave applications routed for approval',
    ],
    url: 'acme.company.example/attendance',
    vignette: <AttendanceVignette />,
  },
  {
    eyebrow: 'Internal mail',
    title: 'A full mail client, isolated per company',
    points: [
      'Conversations with CC/BCC and attachments',
      'Stars, archive, drafts and labels',
      'Powerful filters to find anything fast',
      'On your company’s own mail domain, isolated per company',
    ],
    url: 'mail.company.example',
    vignette: <MailVignette />,
  },
  {
    eyebrow: 'Offboarding',
    title: 'Exits done properly, end to end',
    points: [
      'Exits require leadership approval before anything proceeds',
      'Settlement and separation documents with case-specific terms',
      'An internal clearance checklist across departments',
      'Relieving and experience letters generated with a preview',
      'Account deactivation only when HR says so',
    ],
    url: 'acme.company.example/offboarding',
    vignette: <ClearanceVignette />,
  },
  {
    eyebrow: 'Requests & letters',
    title: 'Requests that route to the right desk',
    points: [
      'Employees request payslips and documents in a few taps',
      'Finance requests route to accountants; letters route to HR',
      'Status tracked end to end, for everyone involved',
    ],
    url: 'acme.company.example/requests',
    vignette: <RequestVignette />,
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
    <Section id="features" className="border-t border-white/5">
      <div className="mx-auto max-w-2xl text-center">
        <Reveal>
          <Eyebrow>What&apos;s inside</Eyebrow>
          <SectionHeading className="mt-4">
            Everything the lifecycle needs, <span className="text-gradient">built in</span>.
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
