'use client';

import * as React from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { ArrowLeft, MailOpen } from 'lucide-react';
import { useApiQuery } from '@/lib/api/hooks';
import { getMessage, mailKeys } from '@/lib/api/mail';
import { absoluteTime, relativeTime } from '@/lib/date';
import { Button } from '@/components/ui/button';
import { EmptyState } from '@/components/empty-state';
import { Skeleton } from '@/components/ui/skeleton';

/**
 * The reading pane (§8). Opening a message fetches it — which stamps the recipient's read_at
 * server-side — so on load we invalidate the unread count + inbox list to reflect the new read state.
 */
export function MessageView({
  messageId,
  onBack,
}: {
  messageId: string | null;
  onBack?: () => void;
}) {
  const queryClient = useQueryClient();
  const query = useApiQuery(
    mailKeys.message(messageId ?? '__none__'),
    (signal) => getMessage(messageId as string, signal),
    { enabled: Boolean(messageId) },
  );

  // Once opened (read_at stamped), refresh the unread badge + inbox rows.
  const loadedId = query.data?.id;
  React.useEffect(() => {
    if (!loadedId) return;
    void queryClient.invalidateQueries({ queryKey: mailKeys.unread });
    void queryClient.invalidateQueries({ queryKey: ['mail', 'inbox'] });
  }, [loadedId, queryClient]);

  if (!messageId) {
    return (
      <EmptyState
        icon={MailOpen}
        title="No message selected"
        description="Choose a message from the list to read it here."
        className="h-full border-0 bg-transparent"
      />
    );
  }

  if (query.isLoading) {
    return (
      <div className="space-y-4 p-6">
        <Skeleton className="h-6 w-2/3" />
        <Skeleton className="h-4 w-1/3" />
        <div className="space-y-2 pt-4">
          <Skeleton className="h-4 w-full" />
          <Skeleton className="h-4 w-full" />
          <Skeleton className="h-4 w-4/5" />
        </div>
      </div>
    );
  }

  if (query.isError || !query.data) {
    return (
      <EmptyState
        icon={MailOpen}
        title="Couldn’t open this message"
        description={query.error?.message ?? 'Please try again.'}
        className="h-full border-0 bg-transparent"
      />
    );
  }

  const m = query.data;

  return (
    <article className="flex h-full flex-col">
      <header className="border-b p-4 md:p-6">
        {onBack ? (
          <Button variant="ghost" size="sm" className="mb-3 md:hidden" onClick={onBack}>
            <ArrowLeft />
            Back
          </Button>
        ) : null}
        <h1 className="text-lg font-semibold tracking-tight">{m.subject}</h1>
        <div className="mt-2 flex flex-wrap items-baseline gap-x-2 gap-y-1 text-sm">
          <span className="font-medium">{m.from.name}</span>
          <span className="text-muted-foreground">&lt;{m.from.address}&gt;</span>
          <span className="text-muted-foreground" title={absoluteTime(m.createdAt)}>
            · {relativeTime(m.createdAt)}
          </span>
        </div>
        {m.to.length > 0 ? (
          <p className="mt-1 text-xs text-muted-foreground">
            To: {m.to.map((t) => `${t.name} <${t.address}>`).join(', ')}
          </p>
        ) : null}
      </header>
      <div className="flex-1 overflow-y-auto p-4 md:p-6">
        <p className="whitespace-pre-wrap break-words text-sm leading-relaxed">{m.body}</p>
      </div>
    </article>
  );
}
