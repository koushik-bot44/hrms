'use client';

import * as React from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useQueryClient } from '@tanstack/react-query';
import { Send } from 'lucide-react';
import { SendMessageSchema, type MailParty, type SendMessageInput } from '@/lib/contract';
import { useApiMutation, useApiQuery } from '@/lib/api/hooks';
import { getMailContacts, mailKeys, sendMessage } from '@/lib/api/mail';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { cn } from '@/lib/utils';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';

/**
 * Compose a NEW thread (§8). The recipient dropdown is populated from `/mail/contacts` — exactly the
 * accounts the send graph allows — so an off-graph or cross-company recipient can never be chosen (the
 * server re-checks and would 403, surfaced as a friendly error). On success the new thread is opened.
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

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<SendMessageInput>({
    resolver: zodResolver(SendMessageSchema),
    defaultValues: { toUserId: presetToUserId ?? '', subject: '', body: '' },
  });

  // Reset the form each time the dialog opens (and honour a preset recipient).
  React.useEffect(() => {
    if (open) reset({ toUserId: presetToUserId ?? '', subject: '', body: '' });
  }, [open, presetToUserId, reset]);

  const mutation = useApiMutation((body: SendMessageInput) => sendMessage(body), {
    successMessage: 'Message sent',
    onSuccess: (result) => {
      void queryClient.invalidateQueries({ queryKey: ['mail', 'sent'] });
      void queryClient.invalidateQueries({ queryKey: mailKeys.unread });
      onOpenChange(false);
      onSent?.(result.threadId);
    },
  });

  const onSubmit = handleSubmit((values) => mutation.mutate(values));
  const options: MailParty[] = contacts.data ?? [];
  const noContacts = contacts.isSuccess && options.length === 0;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>New message</DialogTitle>
          <DialogDescription>
            You can only message people your role connects you to.
          </DialogDescription>
        </DialogHeader>

        <form onSubmit={onSubmit} className="space-y-4" noValidate>
          <div className="space-y-1.5">
            <label htmlFor="mail-to" className="text-sm font-medium">
              To
            </label>
            <select
              id="mail-to"
              disabled={contacts.isLoading || noContacts}
              aria-invalid={Boolean(errors.toUserId)}
              className={cn(
                'flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm',
                'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
                'disabled:cursor-not-allowed disabled:opacity-50 aria-[invalid=true]:border-destructive',
              )}
              {...register('toUserId')}
            >
              <option value="">
                {contacts.isLoading
                  ? 'Loading contacts…'
                  : noContacts
                    ? 'No one to message yet'
                    : 'Select a recipient…'}
              </option>
              {options.map((c) => (
                <option key={c.userId} value={c.userId}>
                  {c.name} · {c.address}
                </option>
              ))}
            </select>
            {errors.toUserId ? (
              <p className="text-xs text-destructive">{errors.toUserId.message}</p>
            ) : null}
          </div>

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

          <div className="flex items-center justify-end gap-2">
            <Button type="button" variant="ghost" onClick={() => onOpenChange(false)}>
              Cancel
            </Button>
            <Button type="submit" disabled={isSubmitting || noContacts}>
              <Send />
              {isSubmitting ? 'Sending…' : 'Send'}
            </Button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}
