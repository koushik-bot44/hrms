'use client';

import * as React from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { FileText, ExternalLink, Send } from 'lucide-react';
import { REQUEST_TYPE_LABELS, RequestType } from '@/lib/contract';
import { getMyLetters, requestLetter } from '@/lib/api/offboarding-docs';
import { useApiQuery, useApiMutation } from '@/lib/api/hooks';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { surface } from '@/components/ui/surface';
import { cn } from '@/lib/utils';

const LETTER_TYPES: RequestType[] = [RequestType.RELIEVING_LETTER, RequestType.EXPERIENCE_LETTER];

/**
 * The employee's letters area in the workspace Offboarding section (§3.6 stage 3). Unlocks once every
 * offboarding document is verified; the employee requests the relieving + experience letters, tracks their
 * status, and downloads them once HR fulfils each.
 */
export function OffboardingLetters() {
  const key = ['my-offboarding-letters'] as const;
  const { data } = useApiQuery(key, getMyLetters);
  const queryClient = useQueryClient();

  const request = useApiMutation((type: RequestType) => requestLetter(type), {
    successMessage: 'Letter requested',
    onSuccess: () => queryClient.invalidateQueries({ queryKey: key }),
  });

  if (!data) return null;

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">Letters</CardTitle>
      </CardHeader>
      <CardContent className="space-y-2">
        {!data.gateOpen ? (
          <p className="text-sm text-muted-foreground">
            Your relieving and experience letters will be available to request once all your offboarding
            documents have been verified.
          </p>
        ) : (
          <>
            <p className="text-sm text-muted-foreground">Request your letters — HR will prepare each one.</p>
            {LETTER_TYPES.map((type) => {
              const existing = data.letters.find((l) => l.type === type);
              const ready = !!existing?.downloadUrl; // issued PDF (or an upload-fulfilled request)
              return (
                <div key={type} className={cn(surface('subtle'), 'flex flex-wrap items-center gap-3 p-3')}>
                  <FileText className="size-4 shrink-0 text-muted-foreground" aria-hidden />
                  <span className="min-w-0 flex-1 truncate text-sm font-medium">
                    {REQUEST_TYPE_LABELS[type]}
                  </span>
                  {existing ? (
                    <Badge variant={ready ? 'success' : 'warning'}>{ready ? 'Ready' : 'Requested'}</Badge>
                  ) : null}
                  {existing?.downloadUrl ? (
                    <a href={existing.downloadUrl} target="_blank" rel="noreferrer">
                      <Button type="button" variant="outline" size="sm">
                        <ExternalLink />
                        Download
                      </Button>
                    </a>
                  ) : null}
                  {!existing ? (
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
                      disabled={request.isPending}
                      onClick={() => request.mutate(type)}
                    >
                      <Send className="size-4" />
                      Request
                    </Button>
                  ) : null}
                </div>
              );
            })}
          </>
        )}
      </CardContent>
    </Card>
  );
}
