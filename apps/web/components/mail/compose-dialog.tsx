'use client';

import * as React from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useQueryClient } from '@tanstack/react-query';
import { Send, X } from 'lucide-react';
import { SendMessageSchema, type MailParty, type SendMessageInput } from '@/lib/contract';
import { useApiMutation, useApiQuery } from '@/lib/api/hooks';
import { getMailContacts, mailKeys, sendMessage } from '@/lib/api/mail';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { cn } from '@/lib/utils';
import {
  AttachmentPicker,
  attachmentsUploading,
  stagedAttachmentIds,
  type StagedAttachment,
} from '@/components/mail/attachment-picker';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';

/**
 * Compose a NEW thread with TO / CC / BCC (§8). Every recipient control is populated from `/mail/contacts`
 * — exactly the accounts the send graph allows — so an off-graph or cross-company recipient can never be
 * chosen (the server re-checks and would 403, surfaced as a friendly error). At least one TO is required.
 */
export function ComposeDialog({
  open,
  onOpenChange,
  onSent,
  presetToUserId,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onSent?: (threadId: string) => void;
  presetToUserId?: string;
}) {
  const queryClient = useQueryClient();
  const contacts = useApiQuery(mailKeys.contacts, getMailContacts, { enabled: open });
  const [staged, setStaged] = React.useState<StagedAttachment[]>([]);
  const [showCcBcc, setShowCcBcc] = React.useState(false);

  const {
    register,
    handleSubmit,
    reset,
    setValue,
    watch,
    formState: { errors, isSubmitting },
  } = useForm<SendMessageInput>({
    resolver: zodResolver(SendMessageSchema),
    defaultValues: {
      toUserIds: presetToUserId ? [presetToUserId] : [],
      ccUserIds: [],
      bccUserIds: [],
      subject: '',
      body: '',
    },
  });

  React.useEffect(() => {
    if (open) {
      reset({
        toUserIds: presetToUserId ? [presetToUserId] : [],
        ccUserIds: [],
        bccUserIds: [],
        subject: '',
        body: '',
      });
      setStaged([]);
      setShowCcBcc(false);
    }
  }, [open, presetToUserId, reset]);

  const mutation = useApiMutation((body: SendMessageInput) => sendMessage(body), {
    successMessage: 'Message sent',
    onSuccess: (result) => {
      void queryClient.invalidateQueries({ queryKey: ['mail', 'sent'] });
      void queryClient.invalidateQueries({ queryKey: mailKeys.unread });
      setStaged([]);
      onOpenChange(false);
      onSent?.(result.threadId);
    },
  });

  const uploading = attachmentsUploading(staged);
  const onSubmit = handleSubmit((values) =>
    mutation.mutate({ ...values, attachmentIds: stagedAttachmentIds(staged) }),
  );

  const options: MailParty[] = contacts.data ?? [];
  const noContacts = contacts.isSuccess && options.length === 0;
  const to = watch('toUserIds') ?? [];
  const cc = watch('ccUserIds') ?? [];
  const bcc = watch('bccUserIds') ?? [];
  // A contact can appear in only one field — exclude any already chosen anywhere.
  const used = new Set<string>([...to, ...cc, ...bcc]);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>New message</DialogTitle>
          <DialogDescription>You can only message people your role connects you to.</DialogDescription>
        </DialogHeader>

        <form onSubmit={onSubmit} className="space-y-4" noValidate>
          <RecipientField
            label="To"
            ids={to}
            options={options}
            used={used}
            disabled={contacts.isLoading || noContacts}
            placeholder={
              contacts.isLoading ? 'Loading contacts…' : noContacts ? 'No one to message yet' : 'Add recipient…'
            }
            onChange={(next) => setValue('toUserIds', next, { shouldValidate: true })}
            error={errors.toUserIds?.message as string | undefined}
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
              <RecipientField
                label="Cc"
                ids={cc}
                options={options}
                used={used}
                disabled={noContacts}
                placeholder="Add Cc…"
                onChange={(next) => setValue('ccUserIds', next, { shouldValidate: true })}
              />
              <RecipientField
                label="Bcc"
                ids={bcc}
                options={options}
                used={used}
                disabled={noContacts}
                placeholder="Add Bcc…"
                onChange={(next) => setValue('bccUserIds', next, { shouldValidate: true })}
              />
            </>
          ) : null}

          <div className="space-y-1.5">
            <label htmlFor="mail-subject" className="text-sm font-medium">
              Subject
            </label>
            <Input
              id="mail-subject"
              placeholder="Subject"
              aria-invalid={Boolean(errors.subject)}
              {...register('subject')}
            />
            {errors.subject ? (
              <p className="text-xs text-destructive">{errors.subject.message}</p>
            ) : null}
          </div>

          <div className="space-y-1.5">
            <label htmlFor="mail-body" className="text-sm font-medium">
              Message
            </label>
            <textarea
              id="mail-body"
              rows={7}
              placeholder="Write your message…"
              aria-invalid={Boolean(errors.body)}
              className={cn(
                'flex w-full rounded-md border border-input bg-background px-3 py-2 text-sm',
                'placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
                'aria-[invalid=true]:border-destructive',
              )}
              {...register('body')}
            />
            {errors.body ? <p className="text-xs text-destructive">{errors.body.message}</p> : null}
          </div>

          <AttachmentPicker staged={staged} setStaged={setStaged} disabled={noContacts} />

          <div className="flex items-center justify-end gap-2">
            <Button type="button" variant="ghost" onClick={() => onOpenChange(false)}>
              Cancel
            </Button>
            <Button type="submit" disabled={isSubmitting || noContacts || uploading}>
              <Send />
              {uploading ? 'Uploading…' : isSubmitting ? 'Sending…' : 'Send'}
            </Button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}

/** A recipient row: chips for chosen contacts + a select to add another (from the allowed contacts only). */
function RecipientField({
  label,
  ids,
  options,
  used,
  disabled,
  placeholder,
  onChange,
  error,
  action,
}: {
  label: string;
  ids: string[];
  options: MailParty[];
  used: Set<string>;
  disabled?: boolean;
  placeholder: string;
  onChange: (next: string[]) => void;
  error?: string;
  action?: React.ReactNode;
}) {
  const byId = React.useMemo(() => new Map(options.map((o) => [o.userId, o])), [options]);
  const available = options.filter((o) => o.userId && !used.has(o.userId));

  return (
    <div className="space-y-1.5">
      <div className="flex items-center justify-between">
        <label className="text-sm font-medium">{label}</label>
        {action}
      </div>
      {ids.length > 0 ? (
        <div className="flex flex-wrap gap-1.5">
          {ids.map((id) => {
            const c = byId.get(id);
            return (
              <span
                key={id}
                className="inline-flex items-center gap-1 rounded-full bg-muted px-2 py-0.5 text-xs"
                title={c?.address}
              >
                {c?.name ?? id}
                <button
                  type="button"
                  aria-label={`Remove ${c?.name ?? id}`}
                  className="text-muted-foreground hover:text-foreground"
                  onClick={() => onChange(ids.filter((x) => x !== id))}
                >
                  <X className="size-3" />
                </button>
              </span>
            );
          })}
        </div>
      ) : null}
      <select
        value=""
        disabled={disabled || available.length === 0}
        aria-invalid={Boolean(error)}
        className={cn(
          'flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm',
          'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
          'disabled:cursor-not-allowed disabled:opacity-50 aria-[invalid=true]:border-destructive',
        )}
        onChange={(e) => {
          if (e.target.value) {
            onChange([...ids, e.target.value]);
          }
        }}
      >
        <option value="">{available.length === 0 ? 'No more contacts' : placeholder}</option>
        {available.map((c) => (
          <option key={c.userId} value={c.userId ?? ''}>
            {c.name} · {c.address}
          </option>
        ))}
      </select>
      {error ? <p className="text-xs text-destructive">{error}</p> : null}
    </div>
  );
}
