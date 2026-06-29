import Link from 'next/link';
import {
  ArrowRight,
  CheckCircle2,
  FileSearch,
  FileSignature,
  Inbox,
  ShieldCheck,
} from 'lucide-react';
import { DocumentStatus, formatUniqueId } from '@cdpp/shared';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { StatusBadge } from '@/components/status-badge';
import { ApiStatusIndicator } from '@/components/api-status-indicator';

// A representative unique ID, built with the shared formatter so the landing page
// reflects the real {ENTITY}-{TYPE}-{YYYY}-{NNNNNN} contract.
const EXAMPLE_ID = formatUniqueId({
  entityCode: 'NAME',
  typeCode: 'OFR',
  year: 2026,
  sequence: 42,
});

const PILLARS = [
  {
    icon: FileSignature,
    title: 'Issue',
    eyebrow: 'Company-authored',
    body: 'Offer letters, appointment letters, and certificates — authored by the organization and issued into the record.',
  },
  {
    icon: Inbox,
    title: 'Collect',
    eyebrow: 'Candidate evidence',
    body: 'Onboarding requirements gathered directly from the candidate, then reviewed before they’re accepted.',
  },
  {
    icon: FileSearch,
    title: 'Reference',
    eyebrow: 'External checks',
    body: 'Background and reference checks run with outside providers and attached to the file.',
  },
] as const;

export default function Home() {
  return (
    <div className="flex flex-col">
      {/* Hero */}
      <section className="border-b bg-gradient-to-b from-secondary/40 to-background">
        <div className="container flex flex-col items-start gap-6 py-20 md:py-28">
          <Badge variant="navy" className="animate-fade-in-up">
            <ShieldCheck className="size-3.5" />
            Document Provisioning Platform
          </Badge>
          <h1 className="max-w-3xl text-3xl font-medium tracking-tight text-foreground sm:text-4xl md:text-5xl">
            A trustworthy system of record for every compliance document.
          </h1>
          <p className="max-w-2xl text-base text-muted-foreground sm:text-lg">
            CDPP issues, collects, and references the documents behind hiring and onboarding —
            each one carrying a unique ID that anyone can verify.
          </p>
          <div className="flex flex-col items-start gap-4 sm:flex-row sm:items-center">
            <Button asChild size="lg">
              <Link href="/verify">
                Verify a document
                <ArrowRight />
              </Link>
            </Button>
            <ApiStatusIndicator />
          </div>
        </div>
      </section>

      {/* Three pillars */}
      <section id="pillars" className="container py-16 md:py-20">
        <div className="mb-10 max-w-2xl">
          <h2 className="text-2xl font-medium tracking-tight">Three ways a record enters the file</h2>
          <p className="mt-2 text-muted-foreground">
            However a document originates, it’s held to the same standard — and verifiable by a
            unique ID.
          </p>
        </div>
        <div className="grid gap-6 md:grid-cols-3">
          {PILLARS.map((pillar) => (
            <Card key={pillar.title}>
              <CardHeader>
                <div className="mb-2 flex size-10 items-center justify-center rounded-lg bg-primary/10 text-primary">
                  <pillar.icon className="size-5" />
                </div>
                <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
                  {pillar.eyebrow}
                </p>
                <CardTitle className="text-lg">{pillar.title}</CardTitle>
                <CardDescription>{pillar.body}</CardDescription>
              </CardHeader>
              <CardContent>
                <p className="inline-flex items-center gap-1.5 text-sm text-success">
                  <CheckCircle2 className="size-4" />
                  Verifiable by a unique ID
                </p>
              </CardContent>
            </Card>
          ))}
        </div>
      </section>

      {/* System of record — unique ID + status */}
      <section className="border-t bg-secondary/30">
        <div className="container grid gap-10 py-16 md:grid-cols-2 md:py-20">
          <div>
            <h2 className="text-2xl font-medium tracking-tight">One ID. One source of truth.</h2>
            <p className="mt-2 max-w-md text-muted-foreground">
              Every document is stamped with a stable, human-readable identifier and carries a
              status anyone authorized can read at a glance.
            </p>
            <div className="mt-6 inline-flex items-center gap-3 rounded-lg border bg-card px-4 py-3 font-mono text-sm shadow-card">
              <ShieldCheck className="size-4 text-primary" />
              <span className="tracking-tight">{EXAMPLE_ID}</span>
            </div>
          </div>
          <div className="flex flex-col gap-3">
            <p className="text-sm font-medium text-muted-foreground">Document lifecycle</p>
            <div className="flex flex-wrap gap-2">
              <StatusBadge status={DocumentStatus.DRAFT} />
              <StatusBadge status={DocumentStatus.PENDING} />
              <StatusBadge status={DocumentStatus.ISSUED} />
              <StatusBadge status={DocumentStatus.SIGNED} />
              <StatusBadge status={DocumentStatus.REVOKED} />
            </div>
          </div>
        </div>
      </section>

      {/* CTA */}
      <section className="container py-16 text-center md:py-20">
        <h2 className="text-2xl font-medium tracking-tight">Have a document to check?</h2>
        <p className="mx-auto mt-2 max-w-md text-muted-foreground">
          Confirm any CDPP record by its unique ID — no account required.
        </p>
        <Button asChild size="lg" className="mt-6">
          <Link href="/verify">
            Go to verification
            <ArrowRight />
          </Link>
        </Button>
      </section>
    </div>
  );
}
