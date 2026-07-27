'use client';

import * as React from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { ArrowLeft, MailMinus, MailOpen, Reply, ReplyAll, Star, Trash2 } from 'lucide-react';
import type { ThreadDetail, ThreadPage } from '@/lib/contract';
import {
  getThread,
  deleteThread,
  mailKeys,
  markThreadUnread,
  starThread,
  unstarThread,
} from '@/lib/api/mail';
import { useApiMutation, useApiQuery } from '@/lib/api/hooks';
import { absoluteTime, relativeTime } from '@/lib/date';
import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import { EmptyState } from '@/components/empty-state';
import { Skeleton } from '@/components/ui/skeleton';
import { AttachmentChips } from '@/components/mail/attachment-chips';
import type { ComposeState } from '@/components/mail/docked-compose';

/**
 * The reading pane (§8 redesign): a thread's messages in order (mine vs theirs) with per-message To/Cc
 * (and Bcc only where the API returns it — the sender's own copy), attachment chips, and Reply / Reply
 * All actions that open the docked composer prefilled. Opening a thread stamps read server-side, so on
 * load we refresh the unread badge + lists.
 */
export function ThreadView({
  threadId,
  onBack,
  onDeleted,
  onReply,
}: {
  threadId: string | null;
  onBack?: () => void;
  onDeleted?: () => void;
  onReply: (state: ComposeState) => void;
}) {
  const queryClient = useQueryClient();
  const query = useApiQuery(
    mailKeys.thread(threadId ?? '__none__'),
    (signal) => getThread(threadId as string, signal),
    { enabled: Boolean(threadId) },
  );

  const refreshLists = React.useCallback(() => {
    void queryClient.invalidateQueries({ queryKey: mailKeys.unread });
    void queryClient.invalidateQueries({ queryKey: ['mail', 'inbox'] });
    void queryClient.invalidateQueries({ queryKey: ['mail', 'sent'] });
    void queryClient.invalidateQueries({ queryKey: ['mail', 'search'] });
  }, [queryClient]);

  // Opening the thread marked it read server-side — reflect that in the badge + lists.
  const loadedId = query.data?.threadId;
  React.useEffect(() => {
    if (loadedId) refreshLists();
  }, [loadedId, refreshLists]);

  const unreadMutation = useApiMutation(() => markThreadUnread(threadId as string), {
    successMessage: 'Marked unread',
    onSuccess: () => {
      refreshLists();
      onDeleted?.(); // leave the reading pane (the thread is now unread in the list)
    },
  });

  const deleteMutation = useApiMutation(() => deleteThread(threadId as string), {
    successMessage: 'Removed from your mailbox',
    onSuccess: () => {
      void queryClient.removeQueries({ queryKey: mailKeys.thread(threadId as string) });
      refreshLists();
      onDeleted?.();
    },
  });

  // Per-user star toggle (thread-level): flip both the open-thread cache and any list page holding it,
  // then reconcile the Starred view (whose membership changed). Optimistic — reverts on failure.
  const currentStarred = query.data?.starred ?? false;
  const patchStar = React.useCallback(
    (next: boolean) => {
      queryClient.setQueryData<ThreadDetail>(mailKeys.thread(threadId as string), (old) =>
        old ? { ...old, starred: next } : old,
      );
      queryClient.setQueriesData<ThreadPage>({ queryKey: ['mail'] }, (old) => {
        if (!old || !Array.isArray(old.content)) return old;
        if (!old.content.some((r) => r.threadId === threadId)) return old;
        return {
          ...old,
          content: old.content.map((r) => (r.threadId === threadId ? { ...r, starred: next } : r)),
        };
      });
    },
    [queryClient, threadId],
  );
  const starMutation = useApiMutation(
    () => (currentStarred ? unstarThread(threadId as string) : starThread(threadId as string)),
    {
      onMutate: () => patchStar(!currentStarred),
      onError: () => patchStar(currentStarred),
      onSettled: () => queryClient.invalidateQueries({ queryKey: ['mail', 'starred'] }),
    },
  );

  if (!threadId) {
    return (
      <EmptyState
        icon={MailOpen}
        title="No conversation selected"
        description="Choose a conversation from the list to read it here."
        className="h-full border-0 bg-transparent"
      />
    );
  }

  if (query.isLoading) {
    return (
      <div className="space-y-4 p-6">
        <Skeleton className="h-6 w-2/3" />
        <Skeleton className="h-20 w-full" />
        <Skeleton className="h-20 w-4/5" />
      </div>
    );
  }

  if (query.isError || !query.data) {
    return (
      <EmptyState
        icon={MailOpen}
        title="Couldn’t open this conversation"
        description={query.error?.message ?? 'It may have been removed.'}
        className="h-full border-0 bg-transparent"
      />
    );
  }

  const thread = query.data;
  const canReply = Boolean(thread.counterparty);
  const canReplyAll = thread.participants.length > 1;

  const openReply = () =>
    onReply({
      mode: 'reply',
      threadId: thread.threadId,
      subject: thread.subject,
      recipients: thread.counterparty ? [thread.counterparty] : [],
    });
  const openReplyAll = () =>
    onReply({
      mode: 'replyAll',
      threadId: thread.threadId,
      subject: thread.subject,
      recipients: thread.participants,
    });

  return (
    <div className="flex h-full flex-col">
      <header className="flex items-start justify-between gap-3 border-b p-4 md:p-6">
        <div className="min-w-0">
          {onBack ? (
            <Button variant="ghost" size="sm" className="mb-3 md:hidden" onClick={onBack}>
              <ArrowLeft />
              Back
            </Button>
          ) : null}
          <h1 className="truncate text-lg font-semibold tracking-tight">{thread.subject}</h1>
          {thread.participants.length > 0 ? (
            <p className="mt-1 truncate text-xs text-muted-foreground">
              With {thread.participants.map((p) => `${p.name} <${p.address}>`).join(', ')}
            </p>
          ) : null}
        </div>
        <div className="flex shrink-0 gap-1">
          <Button
            variant="ghost"
            size="sm"
            onClick={() => starMutation.mutate()}
            disabled={starMutation.isPending}
            aria-pressed={thread.starred}
            title={thread.starred ? 'Starred — click to unstar' : 'Star this conversation'}
            className={cn(thread.starred && 'text-amber-500 hover:text-amber-500 dark:text-amber-400')}
          >
            <Star className={cn(thread.starred && 'fill-current')} />
            <span className="hidden sm:inline">{thread.starred ? 'Starred' : 'Star'}</span>
          </Button>
          <Button
            variant="ghost"
            size="sm"
            onClick={() => unreadMutation.mutate()}
            disabled={unreadMutation.isPending}
            title="Mark unread"
          >
            <MailMinus />
            <span className="hidden sm:inline">Unread</span>
          </Button>
          <Button
            variant="ghost"
            size="sm"
            className="text-destructive hover:text-destructive"
            onClick={() => {
              if (window.confirm('Remove this conversation from YOUR mailbox? The other person keeps their copy.'))
                deleteMutation.mutate();
            }}
            disabled={deleteMutation.isPending}
            title="Delete for me"
          >
            <Trash2 />
            <span className="hidden sm:inline">Delete</span>
          </Button>
        </div>
      </header>

      <div className="flex-1 space-y-3 overflow-y-auto p-4 md:p-6">
        {thread.messages.map((m) => (
          <article
            key={m.id}
            className={cn(
              'max-w-[85%] rounded-lg border p-3 text-sm',
              m.mine ? 'ml-auto bg-primary/5' : 'mr-auto bg-card',
            )}
          >
            <div className="mb-1 flex items-baseline justify-between gap-2">
              <span className="text-xs font-medium">{m.from.name}</span>
              <span className="text-[11px] text-muted-foreground" title={absoluteTime(m.createdAt)}>
                {relativeTime(m.createdAt)}
              </span>
            </div>
            {m.to.length > 0 || m.cc.length > 0 || m.bcc.length > 0 ? (
              <p className="mb-1.5 text-[11px] text-muted-foreground">
                {m.to.length > 0 ? <>To: {m.to.map((p) => p.name).join(', ')}</> : null}
                {m.cc.length > 0 ? <> · Cc: {m.cc.map((p) => p.name).join(', ')}</> : null}
                {/* Bcc is only ever populated for the sender's own copy (or a Bcc recipient themselves). */}
                {m.bcc.length > 0 ? <> · Bcc: {m.bcc.map((p) => p.name).join(', ')}</> : null}
              </p>
            ) : null}
            <p className="whitespace-pre-wrap break-words leading-relaxed">{m.body}</p>
            <AttachmentChips attachments={m.attachments} />
          </article>
        ))}
      </div>

      {/* Reply actions open the docked composer prefilled (recipients derived from the thread). */}
      <div className="flex items-center gap-2 border-t p-3 md:p-4">
        {canReply ? (
          <>
            <Button size="sm" onClick={openReply}>
              <Reply />
              Reply
            </Button>
            {canReplyAll ? (
              <Button size="sm" variant="outline" onClick={openReplyAll}>
                <ReplyAll />
                Reply all
              </Button>
            ) : null}
          </>
        ) : (
          <p className="text-xs text-muted-foreground">You cannot reply to this conversation.</p>
        )}
      </div>
    </div>
  );
}
