import * as React from 'react';
import { Badge } from '@/components/ui/badge';

export interface TierShellProps {
  /** Trust-tier label, e.g. "Tier 1 · Candidate". */
  tier: string;
  title: string;
  description: string;
  children: React.ReactNode;
}

/** Consistent header + container for the trust-tier route groups. */
export function TierShell({ tier, title, description, children }: TierShellProps) {
  return (
    <section className="container py-10">
      <div className="mb-8 max-w-2xl">
        <Badge variant="navy" className="mb-3">
          {tier}
        </Badge>
        <h1 className="text-2xl font-medium tracking-tight">{title}</h1>
        <p className="mt-2 text-muted-foreground">{description}</p>
      </div>
      {children}
    </section>
  );
}
