'use client';

import * as React from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useQueryClient } from '@tanstack/react-query';
import { Calculator, CheckCircle2, Trash2, UserPlus } from 'lucide-react';
import {
  PLATFORM_MAIL_DOMAIN,
  ProvisionAccountantSchema,
  previewAddress,
  type ProvisionAccountantInput,
} from '@/lib/contract';
import { getAccountantStatus, provisionAccountant, removeAccountsAdmin } from '@/lib/api/accountant';
import { useApiMutation, useApiQuery } from '@/lib/api/hooks';
import { generatePassword } from '@/lib/auth/password';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { AddressPreview } from '@/components/mail/address-preview';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog';
import { CredentialNotice } from '@/components/staff-credential-notice';

const STATUS_KEY = ['accountant', 'status'] as const;

/**
 * The single, cross-company READ-ONLY Accounts Admin (§2). One may exist: while none does, this shows a
 * create form; once provisioned, it shows who it is (no second create).
 */
export function CreateAccountantDialog() {
  const [open, setOpen] = React.useState(false);
  const queryClient = useQueryClient();
  const [created, setCreated] = React.useState<{ address: string; password: string } | null>(null);
  const [confirmingRemove, setConfirmingRemove] = React.useState(false);
  const status = useApiQuery(STATUS_KEY, getAccountantStatus);

  const removeMutation = useApiMutation(() => removeAccountsAdmin(), {
    successMessage: 'Accounts Admin removed',
    onSuccess: () => {
      setConfirmingRemove(false);
      void queryClient.invalidateQueries({ queryKey: STATUS_KEY });
    },
  });

  const {
    register,
    handleSubmit,
    reset,
    setValue,
    setError,
    watch,
    formState: { errors, isSubmitting },
  } = useForm<ProvisionAccountantInput>({
    resolver: zodResolver(ProvisionAccountantSchema),
    defaultValues: { name: '', localPart: '', password: '' },
  });

  const localPart = watch('localPart');

  const mutation = useApiMutation((body: ProvisionAccountantInput) => provisionAccountant(body), {
    successMessage: 'Accounts Admin provisioned',
    onSuccess: (result, variables) => {
      const address =
        result.accountant.email ?? previewAddress(variables.localPart, PLATFORM_MAIL_DOMAIN);
      setCreated({ address, password: variables.password });
      reset();
      void queryClient.invalidateQueries({ queryKey: STATUS_KEY });
    },
    onError: (error) => {
      if (error.status === 409) setError('localPart', { message: error.message });
    },
  });

  const exists = status.data?.exists ?? false;
  const onSubmit = handleSubmit((values) => mutation.mutate(values));

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        if (!next) {
          setCreated(null);
          setConfirmingRemove(false);
        }
      }}
    >
      <DialogTrigger asChild>
        <Button variant="outline">
          <Calculator />
          Accounts Admin
        </Button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Accounts Admin</DialogTitle>
          <DialogDescription>
            One central, read-only viewer across all companies — sees approved employees and their
            records, and an approval-only audit trail. Exactly one may exist.
          </DialogDescription>
        </DialogHeader>

        {status.isLoading ? (
          <p className="py-4 text-sm text-muted-foreground">Checking…</p>
        ) : exists ? (
          <div className="space-y-4">
            <div className="flex items-start gap-3 rounded-md border border-success/30 bg-success/5 p-4">
              <CheckCircle2 className="mt-0.5 size-5 shrink-0 text-success" />
              <div className="space-y-1 text-sm">
                <div className="font-medium">An Accounts Admin is already provisioned</div>
                <p className="text-muted-foreground">
                  {status.data?.accountant?.name} · {status.data?.accountant?.email}
                </p>
                <p className="text-xs text-muted-foreground">
                  Only one Accounts Admin may exist. They can change their own password after signing in.
                </p>
              </div>
            </div>

            {confirmingRemove ? (
              <p className="text-xs text-muted-foreground">
                Remove this Accounts Admin to provision a new one. Their sign-in stops working; their
                audit history is kept.
              </p>
            ) : null}
            <div className="flex justify-end gap-2">
              {confirmingRemove ? (
                <>
                  <Button type="button" variant="ghost" onClick={() => setConfirmingRemove(false)}>
                    Cancel
                  </Button>
                  <Button
                    type="button"
                    variant="destructive"
                    disabled={removeMutation.isPending}
                    onClick={() => removeMutation.mutate()}
                  >
                    {removeMutation.isPending ? 'Removing…' : 'Confirm remove'}
                  </Button>
                </>
              ) : (
                <Button
                  type="button"
                  variant="outline"
                  className="text-destructive hover:text-destructive"
                  onClick={() => setConfirmingRemove(true)}
                >
                  <Trash2 />
                  Remove &amp; replace
                </Button>
              )}
            </div>
          </div>
        ) : created ? (
          <CredentialNotice
            title="Accounts Admin provisioned"
            address={created.address}
            password={created.password}
            onDismiss={() => setCreated(null)}
          />
        ) : (
          <form onSubmit={onSubmit} className="space-y-4" noValidate>
            <div className="space-y-1.5">
              <label htmlFor="acc-name" className="text-sm font-medium">
                Name
              </label>
              <Input id="acc-name" placeholder="Casey Counts" aria-invalid={Boolean(errors.name)} {...register('name')} />
              {errors.name ? <p className="text-xs text-destructive">{errors.name.message}</p> : null}
            </div>
            <div className="space-y-1.5">
              <label htmlFor="acc-localpart" className="text-sm font-medium">
                Mailbox Name
              </label>
              <Input
                id="acc-localpart"
                placeholder="accounts"
                autoCapitalize="none"
                spellCheck={false}
                aria-invalid={Boolean(errors.localPart)}
                {...register('localPart')}
              />
              {errors.localPart ? (
                <p className="text-xs text-destructive">{errors.localPart.message}</p>
              ) : (
                <AddressPreview address={previewAddress(localPart, PLATFORM_MAIL_DOMAIN)} />
              )}
            </div>
            <div className="space-y-1.5">
              <div className="flex items-center justify-between">
                <label htmlFor="acc-password" className="text-sm font-medium">
                  Initial Password
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
                id="acc-password"
                type="text"
                autoComplete="off"
                placeholder="At least 8 characters"
                aria-invalid={Boolean(errors.password)}
                {...register('password')}
              />
              {errors.password ? (
                <p className="text-xs text-destructive">{errors.password.message}</p>
              ) : (
                <p className="text-xs text-muted-foreground">
                  They sign in with their address + this password and can change it later.
                </p>
              )}
            </div>
            <Button type="submit" disabled={isSubmitting}>
              <UserPlus />
              {isSubmitting ? 'Provisioning…' : 'Provision Accounts Admin'}
            </Button>
          </form>
        )}
      </DialogContent>
    </Dialog>
  );
}
