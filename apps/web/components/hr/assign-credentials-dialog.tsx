'use client';

import * as React from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useQueryClient } from '@tanstack/react-query';
import { KeyRound } from 'lucide-react';
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
 * the employee's personal address. Opening it again re-issues.
 */
export function AssignCredentialsDialog({
  employeeId,
  personalEmail,
}: {
  employeeId: string;
  personalEmail: string;
}) {
  const { session } = useAuth();
  const queryClient = useQueryClient();
  const [open, setOpen] = React.useState(false);
  const [created, setCreated] = React.useState<{ address: string; password: string } | null>(null);

  // Every mailbox in a company shares its domain — the acting HR's own address carries it.
  const domain = session?.type === 'USER' ? (session.email.split('@')[1] ?? '') : '';

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
      // Generate is the default — pre-fill a strong password (HR may overwrite it).
      reset({ localPart: '', password: generatePassword() });
      setCreated(null);
    }
  };

  const mutation = useApiMutation(
    (body: AssignCredentialsInput) => assignEmployeeCredentials(employeeId, body),
    {
      successMessage: (result) => `Mailbox ${result.mailAddress} assigned`,
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
        <Button type="button" variant="outline" size="sm">
          <KeyRound className="size-4" />
          Assign mailbox
        </Button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Assign mailbox credentials</DialogTitle>
          <DialogDescription>
            Give this approved employee an internal mailbox + password. It is emailed to their personal
            address ({personalEmail}); they can then sign in and message you.
          </DialogDescription>
        </DialogHeader>

        {created ? (
          <div className="space-y-4">
            <CredentialNotice
              title="Mailbox assigned"
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
                <KeyRound />
                {isSubmitting ? 'Assigning…' : 'Assign mailbox'}
              </Button>
            </div>
          </form>
        )}
      </DialogContent>
    </Dialog>
  );
}
