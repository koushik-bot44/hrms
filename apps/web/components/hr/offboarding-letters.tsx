'use client';

import * as React from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { FileText, ExternalLink, Upload } from 'lucide-react';
import { REQUEST_TYPE_LABELS, type LetterView } from '@/lib/contract';
import { getRecordLetters, uploadLetterFile, resolveLetter } from '@/lib/api/offboarding-docs';
import { useApiQuery, useApiMutation } from '@/lib/api/hooks';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { surface } from '@/components/ui/surface';
import { cn } from '@/lib/utils';

const STATUS_TONE: Record<string, 'warning' | 'success' | 'neutral'> = {
  SUBMITTED: 'warning',
  IN_PROGRESS: 'warning',
  RESOLVED: 'success',
  CANCELLED: 'neutral',
};

/**
 * The HR record's offboarding Letters section (§3.6 stage 3). Shows the letter requests the employee made
 * once the gate opened; HR fulfils each by uploading a PDF (reusing the requests fulfil handshake) which the
 * employee can then download.
 */
export function OffboardingLetters({ employeeId }: { employeeId: string }) {
  const key = ['offboarding-letters', employeeId] as const;
  const { data } = useApiQuery(key, (s) => getRecordLetters(employeeId, s));

  if (!data || data.letters.length === 0) {
    return (
      <div className="space-y-2 border-t pt-4">
        <h4 className="text-sm font-semibold text-muted-foreground">Letters</h4>
        <p className="text-sm text-muted-foreground">
          {data?.gateOpen
            ? 'The employee can now request their relieving and experience letters.'
            : 'Letters unlock for the employee once every document is verified.'}
        </p>
      </div>
    );
  }

  return (
    <div className="space-y-2 border-t pt-4">
      <h4 className="text-sm font-semibold text-muted-foreground">Letters</h4>
      <div className="space-y-2">
        {data.letters.map((l) => (
          <LetterRow key={l.type} letter={l} employeeId={employeeId} letterKey={key} />
        ))}
      </div>
    </div>
  );
}

function LetterRow({
  letter,
  employeeId,
  letterKey,
}: {
  letter: LetterView;
  employeeId: string;
  letterKey: readonly unknown[];
}) {
  const queryClient = useQueryClient();
  const inputRef = React.useRef<HTMLInputElement>(null);

  const fulfil = useApiMutation(
    async (file: File) => {
      const docId = await uploadLetterFile(employeeId, letter.type, file);
      return resolveLetter(employeeId, letter.type, [docId]);
    },
    {
      successMessage: 'Letter uploaded and sent to the employee',
      onSuccess: () => queryClient.invalidateQueries({ queryKey: letterKey }),
    },
  );

  return (
    <div className={cn(surface('subtle'), 'flex flex-wrap items-center gap-3 p-3')}>
      <FileText className="size-4 shrink-0 text-muted-foreground" aria-hidden />
      <span className="min-w-0 flex-1 truncate text-sm font-medium">
        {letter.title ?? REQUEST_TYPE_LABELS[letter.type]}
      </span>
      <Badge variant={STATUS_TONE[letter.status] ?? 'neutral'}>
        {letter.status === 'RESOLVED' ? 'Fulfilled' : 'Requested'}
      </Badge>
      {letter.downloadUrl ? (
        <a href={letter.downloadUrl} target="_blank" rel="noreferrer">
          <Button type="button" variant="outline" size="sm">
            <ExternalLink />
            Open
          </Button>
        </a>
      ) : null}
      {letter.status !== 'RESOLVED' ? (
        <>
          <input
            ref={inputRef}
            type="file"
            accept="application/pdf"
            className="hidden"
            onChange={(e) => {
              const file = e.target.files?.[0];
              if (file) fulfil.mutate(file);
              e.target.value = '';
            }}
          />
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={fulfil.isPending}
            onClick={() => inputRef.current?.click()}
          >
            <Upload className="size-4" />
            {fulfil.isPending ? 'Uploading…' : 'Upload PDF'}
          </Button>
        </>
      ) : null}
    </div>
  );
}
