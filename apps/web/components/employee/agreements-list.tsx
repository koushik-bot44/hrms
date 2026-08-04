'use client';

import Link from 'next/link';
import { FileSignature, ChevronRight } from 'lucide-react';
import { getMyAgreements } from '@/lib/api/agreements';
import { useApiQuery } from '@/lib/api/hooks';
import { AGREEMENT_TITLES } from '@/lib/contract';
import { useCompanyPath } from '@/lib/auth/use-company-path';
import { Card, CardContent } from '@/components/ui/card';
import { EmptyState } from '@/components/empty-state';
import { LoadingSkeleton } from '@/components/loading-skeleton';
import { Badge } from '@/components/ui/badge';

/**
 * The employee's post-approval agreements list (§3.5) — the workspace Agreements section. Each row links to
 * the read-and-sign screen; completed agreements show a Completed badge and open the signed PDF.
 */
export function AgreementsList() {
  const { data, isLoading } = useApiQuery(['my-agreements'], getMyAgreements);
  const cp = useCompanyPath();

  if (isLoading) return <LoadingSkeleton lines={4} />;

  if (!data || data.length === 0) {
    return (
      <EmptyState
        icon={FileSignature}
        title="No agreements yet"
        description="When HR sends you company agreements to sign, they will appear here."
      />
    );
  }

  return (
    <Card>
      <CardContent className="space-y-2 pt-6">
        <p className="text-sm text-muted-foreground">
          Please read each agreement in full, fill the required details, and sign.
        </p>
        <ul className="space-y-2">
          {data.map((a) => {
            const done = a.status === 'COMPLETED';
            return (
              <li key={a.type}>
                <Link
                  href={cp(`/workspace/agreements/${a.type}`)}
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
