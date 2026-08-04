'use client';

import Link from 'next/link';
import { FileSignature, ChevronRight } from 'lucide-react';
import { getMyAgreements } from '@/lib/api/agreements';
import { useApiQuery } from '@/lib/api/hooks';
import { AGREEMENT_TITLES } from '@/lib/contract';
import { useCompanyPath } from '@/lib/auth/use-company-path';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';

/**
 * The employee's post-approval agreements (§Agreements). Shown on the onboarding home once HR has sent the
 * pack. Each card links to the read-and-sign screen; completed agreements show a Completed badge and open
 * the signed PDF. Renders nothing until a pack has been sent.
 */
export function AgreementsCard() {
  const { data } = useApiQuery(['my-agreements'], getMyAgreements);
  const cp = useCompanyPath();

  if (!data || data.length === 0) return null;

  const pending = data.filter((a) => a.status !== 'COMPLETED').length;

  return (
    <Card>
      <CardHeader className="flex-row items-center justify-between gap-2 space-y-0">
        <CardTitle className="text-base">Agreements</CardTitle>
        {pending > 0 ? (
          <Badge variant="warning">
            {pending} to sign
          </Badge>
        ) : (
          <Badge variant="success">All signed</Badge>
        )}
      </CardHeader>
      <CardContent className="space-y-2">
        <p className="text-sm text-muted-foreground">
          Please read each agreement in full, fill the required details, and sign.
        </p>
        <ul className="space-y-2">
          {data.map((a) => {
            const done = a.status === 'COMPLETED';
            return (
              <li key={a.type}>
                <Link
                  href={cp(`/employee/agreements/${a.type}`)}
                  className="flex items-center gap-3 rounded-md border p-3 transition-colors hover:bg-muted/50"
                >
                  <FileSignature className="size-4 shrink-0 text-muted-foreground" aria-hidden />
                  <span className="min-w-0 flex-1 truncate text-sm font-medium">
                    {a.title ?? AGREEMENT_TITLES[a.type]}
                  </span>
                  <Badge variant={done ? 'success' : 'warning'}>{done ? 'Completed' : 'To sign'}</Badge>
                  <ChevronRight className="size-4 shrink-0 text-muted-foreground" aria-hidden />
                </Link>
              </li>
            );
          })}
        </ul>
      </CardContent>
    </Card>
  );
}
