import * as React from 'react';
import {
  Building2,
  Calculator,
  Crown,
  Layers,
  Network,
  User,
  UserCheck,
  Users,
} from 'lucide-react';
import { Eyebrow, Section, SectionHeading, Lead } from '@/components/marketing/chrome';
import { Reveal, RevealGroup, RevealItem } from '@/components/marketing/motion';

/**
 * §5 — roles & governance. The eight scoped roles, each seeing exactly their scope, over a faint orbit
 * backdrop; plus the multi-company isolation + Hierarchy oversight note. Roles map to the real RBAC.
 */
const ROLES = [
  { icon: Crown, name: 'Super Admin', scope: 'Platform-wide administration across every company.' },
  { icon: Building2, name: 'Accounts Admin', scope: 'Manages companies and their accounts.' },
  { icon: Network, name: 'Hierarchy', scope: 'Leadership oversight — aggregate analytics and exit approvals.' },
  { icon: Layers, name: 'Company Admin', scope: 'Administers a single company and its teams.' },
  { icon: Users, name: 'HR', scope: 'Onboarding, verification, documents and letters.' },
  { icon: UserCheck, name: 'Manager', scope: 'Approves their team and its requests.' },
  { icon: Calculator, name: 'Accountant', scope: 'Finance requests and payroll-cycle views.' },
  { icon: User, name: 'Employee', scope: 'Their own record, documents, attendance and mail.' },
] as const;

export function Roles() {
  return (
    <Section id="roles" className="relative overflow-hidden border-t border-white/5">
      {/* faint orbit backdrop */}
      <svg
        aria-hidden
        viewBox="0 0 800 800"
        className="pointer-events-none absolute left-1/2 top-1/2 -z-0 h-[720px] w-[720px] -translate-x-1/2 -translate-y-1/2 text-primary-bright opacity-[0.14]"
      >
        {[160, 260, 360].map((r) => (
          <circle key={r} cx={400} cy={400} r={r} fill="none" stroke="currentColor" strokeWidth={1} />
        ))}
      </svg>

      <div className="relative mx-auto max-w-2xl text-center">
        <Reveal>
          <Eyebrow>Roles &amp; governance</Eyebrow>
          <SectionHeading className="mt-4">
            Eight roles, each with <span className="text-gradient">exactly their scope</span>.
          </SectionHeading>
          <Lead className="mx-auto mt-4 max-w-xl">
            Access is scoped by role and partitioned by company, so people see only what they should — with
            leadership oversight across the organization.
          </Lead>
        </Reveal>
      </div>

      <RevealGroup className="relative mt-12 grid gap-3 sm:grid-cols-2 lg:grid-cols-4" gap={0.05}>
        {ROLES.map((r) => {
          const Icon = r.icon;
          return (
            <RevealItem key={r.name}>
              <div className="group h-full rounded-2xl border border-white/10 bg-white/[0.02] p-5 transition-colors hover:border-primary/40 hover:bg-white/[0.04]">
                <span className="flex size-10 items-center justify-center rounded-xl bg-primary/15 text-primary-bright ring-1 ring-inset ring-primary/25">
                  <Icon className="size-5" />
                </span>
                <div className="mt-3 font-semibold text-foreground">{r.name}</div>
                <p className="mt-1 text-sm text-muted-foreground">{r.scope}</p>
              </div>
            </RevealItem>
          );
        })}
      </RevealGroup>

      <Reveal className="relative mt-6" delay={0.1}>
        <div className="grid gap-3 sm:grid-cols-2">
          <div className="rounded-2xl border border-primary/25 bg-primary/[0.06] p-5">
            <div className="font-semibold text-foreground">Multi-company isolation</div>
            <p className="mt-1 text-sm text-muted-foreground">
              Every company is a separate tenant — records, teams and mail stay within their own walls.
            </p>
          </div>
          <div className="rounded-2xl border border-primary/25 bg-primary/[0.06] p-5">
            <div className="font-semibold text-foreground">Leadership oversight</div>
            <p className="mt-1 text-sm text-muted-foreground">
              The Hierarchy role sees aggregate analytics and approves exits across the organization.
            </p>
          </div>
        </div>
      </Reveal>
    </Section>
  );
}
