'use client';

import { toast } from 'sonner';
import { ExternalLink, FileText } from 'lucide-react';
import { GeneratedDocumentKind, type GeneratedDocumentView } from '@/lib/contract';
import { getGeneratedViewUrl } from '@/lib/api/onboarding';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';

const KIND_LABELS: Record<GeneratedDocumentKind, string> = {
  [GeneratedDocumentKind.FORM1]: 'Form 1 — Personal Details',
  [GeneratedDocumentKind.FORM2]: 'Form 2 — Employee Info',
  [GeneratedDocumentKind.FORM3]: 'Form 3 — Previous Employment',
  [GeneratedDocumentKind.FORM4_MANIFEST]: 'Form 4 — Documents',
  [GeneratedDocumentKind.MERGED]: 'Complete Application (merged)',
};

/** Lists the generated onboarding PDFs and opens each via a short-lived, audited presigned URL. */
export function GeneratedDocuments({ documents }: { documents: GeneratedDocumentView[] }) {
  if (documents.length === 0) return null;

  const open = async (id: string) => {
    try {
      const { url } = await getGeneratedViewUrl(id);
      window.open(url, '_blank', 'noopener');
    } catch {
      toast.error('Could not open the document');
    }
  };

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">Your generated forms</CardTitle>
      </CardHeader>
      <CardContent className="grid gap-2 sm:grid-cols-2">
        {documents.map((doc) => (
          <div key={doc.id} className="flex items-center gap-3 rounded-md border p-3">
            <FileText className="size-4 shrink-0 text-muted-foreground" aria-hidden />
            <span className="min-w-0 flex-1 truncate text-sm font-medium">{KIND_LABELS[doc.kind]}</span>
            <Button type="button" variant="outline" size="sm" onClick={() => open(doc.id)}>
              <ExternalLink className="size-4" />
              Open
            </Button>
          </div>
        ))}
      </CardContent>
    </Card>
  );
}
