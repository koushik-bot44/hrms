'use client';

import Link from 'next/link';
import { FileText, ChevronRight } from 'lucide-react';
import { getMyOffboardingDocuments } from '@/lib/api/offboarding-docs';
import { useApiQuery } from '@/lib/api/hooks';
import { OFFBOARDING_DOC_STATUS_LABELS } from '@/lib/contract';
import { useCompanyPath } from '@/lib/auth/use-company-path';
import { Card, CardContent } from '@/components/ui/card';
import { EmptyState } from '@/components/empty-state';
import { LoadingSkeleton } from '@/components/loading-skeleton';
import { Badge } from '@/components/ui/badge';

const TONE: Record<string, 'warning' | 'success' | 'neutral' | 'danger'> = {
  PENDING: 'warning',
  SUBMITTED: 'neutral',
  VERIFIED: 'success',
  REVISION_REQUESTED: 'danger',
};

/**
 * The employee's offboarding documents list (§3.6 stage 2) — the workspace Offboarding section. Each row links
 * to the read-and-sign screen; a document sent back for revision is flagged.
 */
export function OffboardingDocList() {
  const { data, isLoading } = useApiQuery(['my-offboarding-docs'], getMyOffboardingDocuments);
  const cp = useCompanyPath();

  if (isLoading) return <LoadingSkeleton lines={4} />;
  if (!data || data.length === 0) {
    return (
      <EmptyState
        icon={FileText}
        title="No documents yet"
        description="When HR sends you offboarding documents to sign, they will appear here."
      />
    );
  }

  return (
    <Card>
      <CardContent className="space-y-2 pt-6">
        <p className="text-sm text-muted-foreground">
          Read each document in full, complete the details, and sign.
        </p>
        <ul className="space-y-2">
          {data.map((d) => (
            <li key={d.type}>
              <Link
                href={cp(`/workspace/offboarding/${d.type}`)}
                className="flex items-center gap-3 rounded-md border p-3 transition-colors hover:bg-muted/50"
              >
                <FileText className="size-4 shrink-0 text-muted-foreground" aria-hidden />
                <span className="min-w-0 flex-1 truncate text-sm font-medium">{d.title}</span>
                <Badge variant={TONE[d.status] ?? 'neutral'}>
                  {OFFBOARDING_DOC_STATUS_LABELS[d.status] ?? d.status}
                </Badge>
                <ChevronRight className="size-4 shrink-0 text-muted-foreground" aria-hidden />
              </Link>
            </li>
          ))}
        </ul>
      </CardContent>
    </Card>
  );
}
