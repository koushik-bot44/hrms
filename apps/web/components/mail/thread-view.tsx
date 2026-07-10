'use client';

import * as React from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useQueryClient } from '@tanstack/react-query';
import { ArrowLeft, MailMinus, MailOpen, Send, Trash2 } from 'lucide-react';
import {
  ReplyMessageSchema,
  type ReplyMessageInput,
  type ThreadDetail,
  type ThreadMessage,
} from '@/lib/contract';
import { useAuth } from '@/components/auth-provider';
import { useApiMutation, useApiQuery } from '@/lib/api/hooks';
import {
  deleteThread,
  getThread,
  mailKeys,
  markThreadUnread,
  replyToThread,
} from '@/lib/api/mail';
import { absoluteTime, relativeTime } from '@/lib/date';
import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import { EmptyState } from '@/components/empty-state';
import { Skeleton } from '@/components/ui/skeleton';
import { AttachmentChips } from '@/components/mail/attachment-chips';
import {
  AttachmentPicker,
  attachmentsUploading,
  stagedAttachmentIds,
  type StagedAttachment,
} from '@/components/mail/attachment-picker';

/**
 * The reading pane (§8, Stage 3): a thread's messages in order + an inline reply box (the recipient is
 * implicit — derived from the thread) + mark-unread / delete. Opening a thread stamps read server-side,
 * so on load we refresh the unread badge + lists.
 */
export function ThreadView({
  threadId,
  onBack,
  onDeleted,
  onChanged,
}: {
  threadId: string | null;
  onBack?: () => void;
  onDeleted?: () => void;
  onChanged?: () => void;
}) {
  const queryClient = useQueryClient();
  const { session } = useAuth();
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

  const replyForm = useForm<ReplyMessageInput>({
    resolver: zodResolver(ReplyMessageSchema),
    defaultValues: { body: '' },
  });
  const [staged, setStaged] = React.useState<StagedAttachment[]>([]);

  // Clear staged files when switching threads.
  React.useEffect(() => {
    setStaged([]);
    replyForm.reset({ body: '' });
  }, [threadId, replyForm]);

  const replyMutation = useApiMutation(
    (body: ReplyMessageInput) => replyToThread(threadId as string, body),
    {
      successMessage: 'Reply sent',
      onSuccess: (result, vars) => {
        // Optimistically append my reply, then reconcile (the refetch fills in attachments).
        // Works for both a staff USER and a credentialed EMPLOYEE session (§8, Stage 5).
        const mineParty: ThreadMessage['from'] | null = session
          ? session.type === 'USER'
            ? { userId: session.userId, name: session.name, address: session.email, role: session.role }
            : {
                userId: session.employeeId,
                name: session.name ?? session.employeeCode ?? '',
                address: session.mailAddress ?? session.email,
                role: null as unknown as ThreadMessage['from']['role'],
              }
          : null;
        if (mineParty) {
          queryClient.setQueryData<ThreadDetail>(mailKeys.thread(threadId as string), (prev) =>
            prev
              ? {
                  ...prev,
                  messages: [
                    ...prev.messages,
                    {
                      id: result.id,
                      from: mineParty,
                      body: vars.body,
                      createdAt: new Date().toISOString(),
                      mine: true,
                      attachments: [],
                    },
                  ],
                }
              : prev,
          );
        }
        replyForm.reset();
        setStaged([]);
        void queryClient.invalidateQueries({ queryKey: mailKeys.thread(threadId as string) });
        refreshLists();
        onChanged?.();
      },
    },
  );

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
            <p className="whitespace-pre-wrap break-words leading-relaxed">{m.body}</p>
            <AttachmentChips attachments={m.attachments} />
          </article>
        ))}
      </div>

      {/* Inline reply — recipient is implicit (the other participant). */}
      <form
        onSubmit={replyForm.handleSubmit((v) =>
          replyMutation.mutate({ ...v, attachmentIds: stagedAttachmentIds(staged) }),
        )}
        className="border-t p-3 md:p-4"
        noValidate
      >
        {canReply ? (
          <p className="mb-1.5 text-xs text-muted-foreground">
            Reply to <span className="font-medium text-foreground">{thread.counterparty?.address}</span>
          </p>
        ) : null}
        <textarea
          rows={3}
          placeholder={canReply ? 'Write a reply…' : 'You cannot reply to this conversation.'}
          disabled={!canReply}
          aria-invalid={Boolean(replyForm.formState.errors.body)}
          className={cn(
            'flex w-full rounded-md border border-input bg-background px-3 py-2 text-sm',
            'placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
            'disabled:cursor-not-allowed disabled:opacity-50 aria-[invalid=true]:border-destructive',
          )}
          {...replyForm.register('body')}
        />
        {canReply ? (
          <div className="mt-1.5">
            <AttachmentPicker staged={staged} setStaged={setStaged} />
          </div>
        ) : null}
        <div className="mt-2 flex items-center justify-between">
          {replyForm.formState.errors.body ? (
            <p className="text-xs text-destructive">{replyForm.formState.errors.body.message}</p>
          ) : (
            <span />
          )}
          <Button
            type="submit"
            size="sm"
            disabled={!canReply || replyMutation.isPending || attachmentsUploading(staged)}
          >
            <Send />
            {attachmentsUploading(staged) ? 'Uploading…' : replyMutation.isPending ? 'Sending…' : 'Reply'}
          </Button>
        </div>
      </form>
    </div>
  );
}
