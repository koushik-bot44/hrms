'use client';

import Link from 'next/link';
import { ArrowUpRight, CalendarDays, Clock, FileText, Inbox } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { useAuth } from '@/components/auth-provider';
import { Card, CardContent } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';

/** The portal home: an editorial identity hero + the employee's section entry points. */
export default function WorkspacePage() {
  const { session } = useAuth();
  const emp = session?.type === 'EMPLOYEE' ? session : null;

  if (!emp) {
    return (
      <div className="space-y-8">
        <Skeleton className="h-40 w-full rounded-2xl" />
        <Skeleton className="h-32 w-full rounded-2xl" />
      </div>
    );
  }

  return (
    <div className="space-y-8">
      {/* Editorial identity hero — the mint moment. */}
      <Card variant="tint">
        <CardContent className="space-y-6 p-8">
          <div className="space-y-1">
            <p className="text-sm font-medium text-primary">Welcome back</p>
            <h1 className="text-3xl font-semibold tracking-tight text-foreground">
              {emp.name ?? 'Your workspace'}
            </h1>
          </div>
          <div className="grid gap-6 sm:grid-cols-2 lg:grid-cols-3">
            <Detail label="Employee ID" value={emp.employeeCode} mono />
            <Detail label="Mailbox address" value={emp.mailAddress} mono />
          </div>
        </CardContent>
      </Card>

      {/* Section entry points — clickable cards, one per workspace area. */}
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <SectionCard
          href="/mail"
          icon={Inbox}
          title="Mailbox"
          description="Message your HR and read their replies."
        />
        <SectionCard
          href="/workspace/attendance"
          icon={Clock}
          title="Attendance"
          description="Clock in and track your working hours."
        />
        <SectionCard
          href="/workspace/leave"
          icon={CalendarDays}
          title="Leave"
          description="Request time off and track its status."
        />
        <SectionCard
          href="/workspace/requests"
          icon={FileText}
          title="HR/Accounts Requests"
          description="Ask for payslips and other documents."
        />
      </div>
    </div>
  );
}

function SectionCard({
  href,
  icon: Icon,
  title,
  description,
}: {
  href: string;
  icon: LucideIcon;
  title: string;
  description: string;
}) {
  return (
    <Link
      href={href}
      className="block rounded-2xl focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background"
    >
      <Card variant="interactive" className="h-full">
        <CardContent className="flex h-full flex-col gap-4 p-6">
          <div className="flex items-center justify-between">
            <div className="flex size-11 items-center justify-center rounded-2xl bg-primary/10 text-primary">
              <Icon className="size-5" />
            </div>
            <ArrowUpRight className="size-4 text-muted-foreground" aria-hidden />
          </div>
          <div className="space-y-1">
            <p className="font-semibold tracking-tight">{title}</p>
            <p className="text-sm leading-relaxed text-muted-foreground">{description}</p>
          </div>
        </CardContent>
      </Card>
    </Link>
  );
}

function Detail({ label, value, mono }: { label: string; value?: string | null; mono?: boolean }) {
  return (
    <div className="space-y-1">
      <div className="text-xs font-medium uppercase tracking-wide text-muted-foreground">{label}</div>
      <div className={mono ? 'truncate font-mono text-sm' : 'truncate font-medium'}>{value ?? '—'}</div>
    </div>
  );
}
