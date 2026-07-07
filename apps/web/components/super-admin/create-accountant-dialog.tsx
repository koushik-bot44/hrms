'use client';

import * as React from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useQueryClient } from '@tanstack/react-query';
import { Calculator, CheckCircle2, UserPlus } from 'lucide-react';
import { ProvisionAccountantSchema, type ProvisionAccountantInput } from '@/lib/contract';
import { getAccountantStatus, provisionAccountant } from '@/lib/api/accountant';
import { useApiMutation, useApiQuery } from '@/lib/api/hooks';
import { generatePassword } from '@/lib/auth/password';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
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
  const [created, setCreated] = React.useState<{ email: string; password: string } | null>(null);
  const status = useApiQuery(STATUS_KEY, getAccountantStatus);

  const {
    register,
    handleSubmit,
    reset,
    setValue,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<ProvisionAccountantInput>({
    resolver: zodResolver(ProvisionAccountantSchema),
    defaultValues: { name: '', email: '', password: '' },
  });

  const mutation = useApiMutation((body: ProvisionAccountantInput) => provisionAccountant(body), {
    successMessage: 'Accounts Admin provisioned',
    onSuccess: (_result, variables) => {
      setCreated({ email: variables.email, password: variables.password });
      reset();
      void queryClient.invalidateQueries({ queryKey: STATUS_KEY });
    },
    onError: (error) => {
      if (error.status === 409) setError('email', { message: error.message });
    },
  });

  const exists = status.data?.exists ?? false;
  const onSubmit = handleSubmit((values) => mutation.mutate(values));

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        if (!next) setCreated(null);
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
        ) : created ? (
          <CredentialNotice
            title="Accounts Admin provisioned"
            email={created.email}
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
              <label htmlFor="acc-email" className="text-sm font-medium">
                Email
              </label>
              <Input
                id="acc-email"
                type="email"
                placeholder="accounts-admin@portal.com"
                aria-invalid={Boolean(errors.email)}
                {...register('email')}
              />
              {errors.email ? <p className="text-xs text-destructive">{errors.email.message}</p> : null}
            </div>
            <div className="space-y-1.5">
              <div className="flex items-center justify-between">
                <label htmlFor="acc-password" className="text-sm font-medium">
                  Initial password
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
                  They sign in with email + this password and can change it later.
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
