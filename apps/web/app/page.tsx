import Link from 'next/link';
import { ArrowRight, ClipboardCheck, ScrollText, ShieldCheck, UserPlus } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';

const FEATURES = [
  {
    icon: UserPlus,
    title: 'HR-initiated onboarding',
    body: 'HR triggers onboarding; the new employee gets a unique ID and fills guided, tabbed forms.',
  },
  {
    icon: ClipboardCheck,
    title: 'Verify & approve',
    body: 'HR verifies identity and background documents; the team’s Manager gives the final approval.',
  },
  {
    icon: ScrollText,
    title: 'Audited & multi-tenant',
    body: 'Every action is logged and partitioned per company, with strict role-scoped access.',
  },
] as const;

export default function Home() {
  return (
    <div className="flex min-h-dvh flex-col">
      <header className="flex h-14 items-center justify-between border-b px-6">
        <div className="flex items-center gap-2">
          <div className="flex size-7 items-center justify-center rounded-md bg-primary text-primary-foreground">
            <ShieldCheck className="size-4" />
          </div>
          <span className="font-semibold tracking-tight">HRORGS</span>
        </div>
        <div className="flex items-center gap-4">
          <Button asChild size="sm" variant="outline">
            <Link href="/login">Sign in</Link>
          </Button>
        </div>
      </header>

      <main className="flex flex-1 items-center">
        <div className="container grid gap-12 py-16 md:grid-cols-2 md:py-24">
          <div className="flex flex-col justify-center gap-6">
            <Badge variant="primarySoft" className="w-fit">
              Employee information & onboarding
            </Badge>
            <h1 className="text-3xl font-medium tracking-tight sm:text-4xl md:text-5xl">
              A calm, secure home for every employee record.
            </h1>
            <p className="max-w-md text-muted-foreground">
              HRORGS manages onboarding, identity & background documents, and approvals under one
              unique employee ID — multi-tenant, audited, and scoped to the role hierarchy.
            </p>
            <div className="flex flex-wrap gap-3">
              <Button asChild size="lg">
                <Link href="/login">
                  Get started
                  <ArrowRight />
                </Link>
              </Button>
            </div>
          </div>

          <div className="grid content-center gap-4">
            {FEATURES.map((f) => (
              <Card key={f.title}>
                <CardHeader className="flex-row items-start gap-4 space-y-0">
                  <div className="flex size-9 shrink-0 items-center justify-center rounded-md bg-primary/10 text-primary">
                    <f.icon className="size-5" />
                  </div>
                  <div className="space-y-1">
                    <CardTitle className="text-base">{f.title}</CardTitle>
                    <CardDescription>{f.body}</CardDescription>
                  </div>
                </CardHeader>
              </Card>
            ))}
          </div>
        </div>
      </main>
    </div>
  );
}
