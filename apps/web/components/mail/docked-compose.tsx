'use client';

import * as React from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Maximize2, Minimize2, Minus, Save, Send, X } from 'lucide-react';
import type { MailParty, SaveDraftInput } from '@/lib/contract';
import { useApiMutation, useApiQuery } from '@/lib/api/hooks';
import {
  createDraft,
  deleteDraft,
  getMailContacts,
  mailKeys,
  replyAllToThread,
  replyToThread,
  sendDraft,
  sendMessage,
  updateDraft,
} from '@/lib/api/mail';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { cn } from '@/lib/utils';
import { surface } from '@/components/ui/surface';
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
  /** Draft reopen: the draft being edited (Send routes through /drafts/{id}/send; edits update it). */
  draftId?: string;
  /** Draft reopen (new-compose): prefilled To/Cc/Bcc, subject, body, and already-uploaded attachments. */
  initialTo?: MailParty[];
  initialCc?: MailParty[];
  initialBcc?: MailParty[];
  initialSubject?: string;
  initialBody?: string;
  initialAttachments?: DraftAttachmentSeed[];
};

/** A draft's already-uploaded attachment, reconstructed as a "done" staged file for the picker. */
export type DraftAttachmentSeed = { attachmentId: string; name: string; size: number };

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
  const [showCcBcc, setShowCcBcc] = React.useState(
    (state.initialCc?.length ?? 0) > 0 || (state.initialBcc?.length ?? 0) > 0,
  );
  const [to, setTo] = React.useState<MailParty[]>(state.presetTo ?? state.initialTo ?? []);
  const [cc, setCc] = React.useState<MailParty[]>(state.initialCc ?? []);
  const [bcc, setBcc] = React.useState<MailParty[]>(state.initialBcc ?? []);
  const [subject, setSubject] = React.useState(state.initialSubject ?? state.subject ?? '');
  const [body, setBody] = React.useState(state.initialBody ?? '');
  const [staged, setStaged] = React.useState<StagedAttachment[]>(() =>
    (state.initialAttachments ?? []).map((a) => ({
      uid: a.attachmentId,
      name: a.name,
      size: a.size,
      progress: 100,
      status: 'done' as const,
      attachmentId: a.attachmentId,
    })),
  );
  const [error, setError] = React.useState<string | null>(null);
  // The draft this composer is bound to (set when reopened, or after the first Save draft).
  const [draftId, setDraftId] = React.useState<string | undefined>(state.draftId);

  const title = state.mode === 'reply' ? 'Reply' : state.mode === 'replyAll' ? 'Reply all' : 'New message';
  const ids = (list: MailParty[]) => list.map((p) => p.userId!).filter(Boolean);

  const invalidateDrafts = () =>
    void queryClient.invalidateQueries({ queryKey: ['mail', 'drafts'] });

  const finish = (threadId: string) => {
    void queryClient.invalidateQueries({ queryKey: ['mail', 'sent'] });
    void queryClient.invalidateQueries({ queryKey: mailKeys.unread });
    invalidateDrafts(); // a sent draft leaves Drafts
    if (state.threadId) {
      void queryClient.invalidateQueries({ queryKey: mailKeys.thread(state.threadId) });
    }
    onSent?.(threadId);
    onClose();
  };

  // The current composition as a permissive draft payload (nothing is required or graph-checked).
  const draftPayload = (): SaveDraftInput => ({
    toUserIds: ids(to),
    ccUserIds: ids(cc),
    bccUserIds: ids(bcc),
    subject: subject,
    body: body,
    attachmentIds: stagedAttachmentIds(staged),
    replyToThreadId: state.threadId,
    replyAll: state.mode === 'replyAll',
  });

  const hasContent = () =>
    to.length > 0 ||
    cc.length > 0 ||
    bcc.length > 0 ||
    subject.trim().length > 0 ||
    body.trim().length > 0 ||
    stagedAttachmentIds(staged).length > 0;

  const sendMut = useApiMutation(
    async () => {
      // A reopened/saved draft: persist the latest edits, then run the real send (which deletes the draft).
      if (draftId) {
        await updateDraft(draftId, draftPayload());
        return sendDraft(draftId);
      }
      if (state.mode === 'new') {
        return sendMessage({
          toUserIds: ids(to),
          ccUserIds: ids(cc),
          bccUserIds: ids(bcc),
          subject: subject.trim(),
          body: body.trim(),
          attachmentIds: stagedAttachmentIds(staged),
        });
      }
      const payload = { body: body.trim(), attachmentIds: stagedAttachmentIds(staged) };
      return state.mode === 'replyAll'
        ? replyAllToThread(state.threadId!, payload)
        : replyToThread(state.threadId!, payload);
    },
    {
      successMessage:
        state.mode === 'replyAll' ? 'Reply sent to everyone' : state.mode === 'reply' ? 'Reply sent' : 'Message sent',
      onSuccess: (r) => finish(r.threadId),
    },
  );

  // Save draft (explicit, or on close-with-content). Create the first time, then update.
  const saveMut = useApiMutation(
    () => (draftId ? updateDraft(draftId, draftPayload()) : createDraft(draftPayload())),
    {
      successMessage: 'Draft saved',
      onSuccess: (d) => {
        setDraftId(d.id);
        invalidateDrafts();
      },
    },
  );

  const discardMut = useApiMutation(() => (draftId ? deleteDraft(draftId) : Promise.resolve()), {
    onSuccess: () => {
      invalidateDrafts();
      onClose();
    },
  });

  const busy = sendMut.isPending || attachmentsUploading(staged);

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
    }
    sendMut.mutate();
  };

  // Closing the composer keeps your work: save it as a draft when there's content (Gmail-style); an
  // emptied draft is cleaned up. "Discard" is the explicit throw-away.
  const handleClose = () => {
    if (busy || sendMut.isSuccess) {
      onClose();
      return;
    }
    if (hasContent()) {
      saveMut.mutate(undefined, { onSuccess: () => onClose() });
      return;
    }
    if (draftId) {
      discardMut.mutate(); // emptied a previously-saved draft -> discard it
      return;
    }
    onClose();
  };

  const handleDiscard = () => {
    if (draftId) {
      discardMut.mutate();
    } else {
      onClose();
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
          <IconBtn label="Close" onClick={handleClose}>
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
            <div className={cn(surface('subtle'), 'px-3 py-2 text-xs')}>
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

          <div className="mt-auto flex items-center gap-2 pt-1">
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
            <Button
              variant="outline"
              size="sm"
              disabled={busy || saveMut.isPending}
              onClick={() => saveMut.mutate()}
              title="Save as a draft (not sent)"
            >
              <Save />
              {saveMut.isPending ? 'Saving…' : 'Save draft'}
            </Button>
            <Button
              variant="ghost"
              size="sm"
              className="ml-auto text-muted-foreground"
              disabled={discardMut.isPending}
              onClick={handleDiscard}
            >
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
