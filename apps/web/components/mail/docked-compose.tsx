'use client';

import * as React from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Maximize2, Minimize2, Minus, Send, X } from 'lucide-react';
import type { MailParty } from '@/lib/contract';
import { useApiMutation, useApiQuery } from '@/lib/api/hooks';
import {
  getMailContacts,
  mailKeys,
  replyAllToThread,
  replyToThread,
  sendMessage,
} from '@/lib/api/mail';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { cn } from '@/lib/utils';
import { RecipientAutocomplete } from '@/components/mail/recipient-autocomplete';
import {
  AttachmentPicker,
  attachmentsUploading,
  stagedAttachmentIds,
  type StagedAttachment,
} from '@/components/mail/attachment-picker';

export type ComposeState = {
  mode: 'new' | 'reply' | 'replyAll';
  /** reply/replyAll: the thread to reply into. */
  threadId?: string;
  /** reply/replyAll: the thread subject (shown, read-only — kept by the server). */
  subject?: string;
  /** reply/replyAll: the derived recipients to show (read-only; the server re-derives + re-validates). */
  recipients?: MailParty[];
  /** new: an optional preset recipient (e.g. "message this contact"). */
  presetTo?: MailParty[];
};

/**
 * The docked compose window (§8 redesign) — bottom-right, Gmail-style, with minimise / expand / close.
 * NEW compose has type-to-search To/Cc/Bcc (allowed contacts only) and posts to the send endpoint;
 * Reply / Reply All prefill the derived recipients (read-only — the reply endpoints derive + re-validate
 * them server-side to keep threading + the send graph) and post to the reply / reply-all endpoints.
 */
export function DockedCompose({
  state,
  onClose,
  onSent,
}: {
  state: ComposeState;
  onClose: () => void;
  onSent?: (threadId: string) => void;
}) {
  const queryClient = useQueryClient();
  const isReply = state.mode !== 'new';
  const contacts = useApiQuery(mailKeys.contacts, getMailContacts, { enabled: state.mode === 'new' });
  const options = contacts.data ?? [];

  const [minimised, setMinimised] = React.useState(false);
  const [maximised, setMaximised] = React.useState(false);
  const [showCcBcc, setShowCcBcc] = React.useState(false);
  const [to, setTo] = React.useState<MailParty[]>(state.presetTo ?? []);
  const [cc, setCc] = React.useState<MailParty[]>([]);
  const [bcc, setBcc] = React.useState<MailParty[]>([]);
  const [subject, setSubject] = React.useState(state.subject ?? '');
  const [body, setBody] = React.useState('');
  const [staged, setStaged] = React.useState<StagedAttachment[]>([]);
  const [error, setError] = React.useState<string | null>(null);

  const title = state.mode === 'reply' ? 'Reply' : state.mode === 'replyAll' ? 'Reply all' : 'New message';
  const ids = (list: MailParty[]) => list.map((p) => p.userId!).filter(Boolean);

  const finish = (threadId: string) => {
    void queryClient.invalidateQueries({ queryKey: ['mail', 'sent'] });
    void queryClient.invalidateQueries({ queryKey: mailKeys.unread });
    if (state.threadId) {
      void queryClient.invalidateQueries({ queryKey: mailKeys.thread(state.threadId) });
    }
    onSent?.(threadId);
    onClose();
  };

  const sendMut = useApiMutation(
    () =>
      sendMessage({
        toUserIds: ids(to),
        ccUserIds: ids(cc),
        bccUserIds: ids(bcc),
        subject: subject.trim(),
        body: body.trim(),
        attachmentIds: stagedAttachmentIds(staged),
      }),
    { successMessage: 'Message sent', onSuccess: (r) => finish(r.threadId) },
  );
  const replyMut = useApiMutation(
    () => {
      const payload = { body: body.trim(), attachmentIds: stagedAttachmentIds(staged) };
      return state.mode === 'replyAll'
        ? replyAllToThread(state.threadId!, payload)
        : replyToThread(state.threadId!, payload);
    },
    {
      successMessage: state.mode === 'replyAll' ? 'Reply sent to everyone' : 'Reply sent',
      onSuccess: (r) => finish(r.threadId),
    },
  );

  const busy = sendMut.isPending || replyMut.isPending || attachmentsUploading(staged);

  const submit = () => {
    setError(null);
    if (!body.trim()) {
      setError('Write a message.');
      return;
    }
    if (state.mode === 'new') {
      if (to.length === 0) {
        setError('Add at least one recipient.');
        return;
      }
      if (!subject.trim()) {
        setError('Add a subject.');
        return;
      }
      sendMut.mutate();
    } else {
      replyMut.mutate();
    }
  };

  const idsOf = (list: MailParty[]) => new Set(ids(list));

  return (
    <div
      role="dialog"
      aria-label={title}
      className={cn(
        'fixed z-40 flex flex-col overflow-hidden border bg-card shadow-2xl',
        maximised
          ? 'inset-3 rounded-lg md:inset-10'
          : 'bottom-0 right-3 w-[min(36rem,calc(100vw-1.5rem))] rounded-t-lg md:right-6',
      )}
    >
      {/* Title bar (click to toggle minimise) */}
      <div
        className="flex cursor-pointer items-center justify-between gap-2 bg-primary px-3 py-2 text-primary-foreground"
        onClick={() => setMinimised((m) => !m)}
      >
        <span className="truncate text-sm font-medium">{title}</span>
        <div className="flex items-center gap-0.5" onClick={(e) => e.stopPropagation()}>
          <IconBtn label="Minimise" onClick={() => setMinimised((m) => !m)}>
            <Minus className="size-4" />
          </IconBtn>
          <IconBtn label={maximised ? 'Restore' : 'Maximise'} onClick={() => setMaximised((m) => !m)}>
            {maximised ? <Minimize2 className="size-4" /> : <Maximize2 className="size-4" />}
          </IconBtn>
          <IconBtn label="Close" onClick={onClose}>
            <X className="size-4" />
          </IconBtn>
        </div>
      </div>

      {!minimised ? (
        <div
          className={cn(
            'flex min-h-0 flex-1 flex-col gap-2 overflow-y-auto p-3',
            !maximised && 'max-h-[82vh]',
          )}
        >
          {state.mode === 'new' ? (
            <>
              <RecipientAutocomplete
                id="cmp-to"
                label="To"
                selected={to}
                options={options}
                excludeIds={idsOf([...cc, ...bcc])}
                onChange={setTo}
                autoFocus
                invalid={Boolean(error && to.length === 0)}
                action={
                  !showCcBcc ? (
                    <button
                      type="button"
                      className="text-xs text-muted-foreground hover:text-foreground"
                      onClick={() => setShowCcBcc(true)}
                    >
                      Cc/Bcc
                    </button>
                  ) : null
                }
              />
              {showCcBcc ? (
                <>
                  <RecipientAutocomplete
                    id="cmp-cc"
                    label="Cc"
                    selected={cc}
                    options={options}
                    excludeIds={idsOf([...to, ...bcc])}
                    onChange={setCc}
                  />
                  <RecipientAutocomplete
                    id="cmp-bcc"
                    label="Bcc"
                    selected={bcc}
                    options={options}
                    excludeIds={idsOf([...to, ...cc])}
                    onChange={setBcc}
                  />
                </>
              ) : null}
              <Input
                aria-label="Subject"
                placeholder="Subject"
                value={subject}
                onChange={(e) => setSubject(e.target.value)}
              />
            </>
          ) : (
            <div className="rounded-md bg-muted px-3 py-2 text-xs">
              <div className="truncate">
                <span className="text-muted-foreground">To </span>
                <span className="font-medium">
                  {state.recipients && state.recipients.length > 0
                    ? state.recipients.map((r) => r.name).join(', ')
                    : 'the conversation'}
                </span>
              </div>
              {state.subject ? (
                <div className="mt-0.5 truncate font-medium text-foreground">{state.subject}</div>
              ) : null}
            </div>
          )}

          <textarea
            aria-label="Message"
            value={body}
            onChange={(e) => setBody(e.target.value)}
            autoFocus={isReply}
            placeholder="Write your message…"
            className={cn(
              'w-full flex-1 rounded-md border border-input bg-background px-3 py-2 text-sm',
              maximised ? 'min-h-56' : 'min-h-44',
              'placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
            )}
          />

          {error ? <p className="text-xs text-destructive">{error}</p> : null}

          <AttachmentPicker staged={staged} setStaged={setStaged} />

          <div className="mt-auto flex items-center justify-between pt-1">
            <Button size="sm" disabled={busy} onClick={submit}>
              <Send />
              {attachmentsUploading(staged)
                ? 'Uploading…'
                : busy
                  ? 'Sending…'
                  : state.mode === 'replyAll'
                    ? 'Send to all'
                    : 'Send'}
            </Button>
            <Button variant="ghost" size="sm" onClick={onClose}>
              Discard
            </Button>
          </div>
        </div>
      ) : null}
    </div>
  );
}

function IconBtn({
  label,
  onClick,
  children,
}: {
  label: string;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      aria-label={label}
      title={label}
      onClick={onClick}
      className="rounded p-1 text-primary-foreground/80 hover:bg-white/15 hover:text-primary-foreground"
    >
      {children}
    </button>
  );
}
