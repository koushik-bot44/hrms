'use client';

import * as React from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useQueryClient } from '@tanstack/react-query';
import { KeyRound, RotateCcw } from 'lucide-react';
import {
  AssignCredentialsSchema,
  previewAddress,
  type AssignCredentialsInput,
} from '@/lib/contract';
import { assignEmployeeCredentials } from '@/lib/api/review';
import { useApiMutation } from '@/lib/api/hooks';
import { useAuth } from '@/components/auth-provider';
import { generatePassword } from '@/lib/auth/password';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { CredentialNotice } from '@/components/staff-credential-notice';
import { AddressPreview } from '@/components/mail/address-preview';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog';

/**
 * HR assigns an APPROVED employee internal credentials (§8, Stage 5): a mailbox address + password.
 * The address forms `localpart@companyDomain` (the HR's own domain); the password is pre-filled with a
 * generated one (the default) but can be typed. On success the credentials are shown once and emailed to
 * the employee's personal address.
 *
 * Two modes over the SAME endpoint (re-issue = re-POST): {@code assign} is first assignment (the primary
 * button, shown only while the employee has no mailbox yet); {@code reset} is the demoted recovery action
 * shown once a mailbox exists — it regenerates the password and re-emails it (the current one stops
 * working), so its copy makes clear this is recovery, not a fresh assignment.
 */
export function AssignCredentialsDialog({
  employeeId,
  personalEmail,
  mailDomain,
  mode = 'assign',
  initialLocalPart = '',
}: {
  employeeId: string;
  personalEmail: string;
  /** The company's mail domain (the address the server will actually create). */
  mailDomain?: string | null;
  mode?: 'assign' | 'reset';
  /** For reset: pre-fill the existing mailbox name so HR keeps the same address by default. */
  initialLocalPart?: string;
}) {
  const isReset = mode === 'reset';
  const { session } = useAuth();
  const queryClient = useQueryClient();
  const [open, setOpen] = React.useState(false);
  const [created, setCreated] = React.useState<{ address: string; password: string } | null>(null);

  // Preview the address the server will ACTUALLY create — the company's mail domain (localpart@mailDomain).
  // Only if it's somehow absent do we fall back to guessing from the acting HR's own login email domain,
  // which is wrong whenever the HR's email domain differs from the company's configured mail domain.
  const domain =
    mailDomain || (session?.type === 'USER' ? (session.email.split('@')[1] ?? '') : '');

  const {
    register,
    handleSubmit,
    reset,
    setValue,
    setError,
    watch,
    formState: { errors, isSubmitting },
  } = useForm<AssignCredentialsInput>({
    resolver: zodResolver(AssignCredentialsSchema),
    defaultValues: { localPart: '', password: '' },
  });
  const localPart = watch('localPart');

  const openChange = (next: boolean) => {
    setOpen(next);
    if (next) {
      // Generate is the default — pre-fill a strong password (HR may overwrite it). For reset, keep the
      // existing mailbox name so the address stays the same unless HR deliberately changes it.
      reset({ localPart: isReset ? initialLocalPart : '', password: generatePassword() });
      setCreated(null);
    }
  };

  const mutation = useApiMutation(
    (body: AssignCredentialsInput) => assignEmployeeCredentials(employeeId, body),
    {
      successMessage: (result) =>
        isReset ? `New password sent for ${result.mailAddress}` : `Mailbox ${result.mailAddress} assigned`,
      onSuccess: (result, variables) => {
        setCreated({ address: result.mailAddress, password: variables.password });
        void queryClient.invalidateQueries({ queryKey: ['hr-record', employeeId] });
      },
      onError: (error) => {
        if (error.status === 409) setError('localPart', { message: error.message });
      },
    },
  );

  const onSubmit = handleSubmit((values) => mutation.mutate(values));

  return (
    <Dialog open={open} onOpenChange={openChange}>
      <DialogTrigger asChild>
        {isReset ? (
          // Demoted recovery action — a quiet link, not a primary button.
          <Button
            type="button"
            variant="ghost"
            size="sm"
            className="h-auto px-1.5 py-0.5 text-xs text-muted-foreground hover:text-foreground"
          >
            <RotateCcw className="size-3.5" />
            Reset credentials
          </Button>
        ) : (
          <Button type="button" variant="outline" size="sm">
            <KeyRound className="size-4" />
            Assign mailbox
          </Button>
        )}
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{isReset ? 'Reset mailbox credentials' : 'Assign mailbox credentials'}</DialogTitle>
          <DialogDescription>
            {isReset ? (
              <>
                Generate a <span className="font-medium text-foreground">new password</span> and re-email it
                to {personalEmail}. This is for recovery — the employee&rsquo;s current password will stop
                working. Keep the mailbox name to keep the same address.
              </>
            ) : (
              <>
                Give this approved employee an internal mailbox + password. It is emailed to their personal
                address ({personalEmail}); they can then sign in and message you.
              </>
            )}
          </DialogDescription>
        </DialogHeader>

        {created ? (
          <div className="space-y-4">
            <CredentialNotice
              title={isReset ? 'New password issued' : 'Mailbox assigned'}
              address={created.address}
              password={created.password}
              onDismiss={() => setCreated(null)}
            />
            <p className="text-xs text-muted-foreground">
              Also emailed to {personalEmail} with the sign-in link.
            </p>
            <div className="flex justify-end">
              <Button type="button" onClick={() => setOpen(false)}>
                Done
              </Button>
            </div>
          </div>
        ) : (
          <form onSubmit={onSubmit} className="space-y-4" noValidate>
            <div className="space-y-1.5">
              <label htmlFor="cred-localpart" className="text-sm font-medium">
                Mailbox name
              </label>
              <Input
                id="cred-localpart"
                placeholder="firstname"
                autoCapitalize="none"
                spellCheck={false}
                aria-invalid={Boolean(errors.localPart)}
                {...register('localPart')}
              />
              {errors.localPart ? (
                <p className="text-xs text-destructive">{errors.localPart.message}</p>
              ) : (
                <AddressPreview address={previewAddress(localPart, domain)} />
              )}
            </div>
            <div className="space-y-1.5">
              <div className="flex items-center justify-between">
                <label htmlFor="cred-password" className="text-sm font-medium">
                  Password
                </label>
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  onClick={() => setValue('password', generatePassword(), { shouldValidate: true })}
                >
                  Generate
                </Button>
              </div>
              <Input
                id="cred-password"
                type="text"
                autoComplete="off"
                aria-invalid={Boolean(errors.password)}
                {...register('password')}
              />
              {errors.password ? (
                <p className="text-xs text-destructive">{errors.password.message}</p>
              ) : (
                <p className="text-xs text-muted-foreground">
                  Pre-filled with a strong password — edit it if you prefer. Shown once after assigning.
                </p>
              )}
            </div>
            <div className="flex justify-end gap-2">
              <Button type="button" variant="ghost" onClick={() => setOpen(false)}>
                Cancel
              </Button>
              <Button type="submit" disabled={isSubmitting}>
                {isReset ? <RotateCcw /> : <KeyRound />}
                {isReset
                  ? isSubmitting
                    ? 'Resetting…'
                    : 'Reset credentials'
                  : isSubmitting
                    ? 'Assigning…'
                    : 'Assign mailbox'}
              </Button>
            </div>
          </form>
        )}
      </DialogContent>
    </Dialog>
  );
}
