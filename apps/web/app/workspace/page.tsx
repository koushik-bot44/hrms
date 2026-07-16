'use client';

import Link from 'next/link';
import { ArrowRight, Mail } from 'lucide-react';
import { useAuth } from '@/components/auth-provider';
import { PageHeader } from '@/components/page-header';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';

/** The portal home: the employee's identity + their sections (Mailbox for now). */
export default function WorkspacePage() {
  const { session } = useAuth();
  const emp = session?.type === 'EMPLOYEE' ? session : null;

  if (!emp) {
    return (
      <div className="space-y-6">
        <Skeleton className="h-8 w-56" />
        <Skeleton className="h-32 w-full" />
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <PageHeader
        title={emp.name ? `Welcome, ${emp.name}` : 'Welcome'}
        description="Your employee workspace."
      />

      <Card>
        <CardHeader>
          <CardTitle className="text-base">Your details</CardTitle>
        </CardHeader>
        <CardContent className="grid gap-4 text-sm sm:grid-cols-3">
          <Detail label="Name" value={emp.name} />
          <Detail label="Employee ID" value={emp.employeeCode} mono />
          <Detail label="Mailbox Address" value={emp.mailAddress} mono />
        </CardContent>
      </Card>

      <Card>
        <CardHeader className="flex-col gap-3 sm:flex-row sm:items-start sm:justify-between sm:space-y-0">
          <div className="flex items-start gap-3">
            <div className="flex size-9 shrink-0 items-center justify-center rounded-md bg-primary/10 text-primary">
              <Mail className="size-5" />
            </div>
            <div className="space-y-1">
              <CardTitle className="text-base">Mailbox</CardTitle>
              <CardDescription>
                Message your HR — ask questions and read their replies. Internal only.
              </CardDescription>
            </div>
          </div>
          <Button asChild size="sm" className="w-fit">
            <Link href="/mail">
              Open mailbox
              <ArrowRight />
            </Link>
          </Button>
        </CardHeader>
      </Card>
    </div>
  );
}

function Detail({ label, value, mono }: { label: string; value?: string | null; mono?: boolean }) {
  return (
    <div className="space-y-0.5">
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className={mono ? 'truncate font-mono' : 'truncate font-medium'}>{value ?? '—'}</div>
    </div>
  );
}
