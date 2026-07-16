'use client';

import * as React from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useQueryClient } from '@tanstack/react-query';
import { UserPlus } from 'lucide-react';
import {
  ProvisionCompanyAdminSchema,
  previewAddress,
  type ProvisionCompanyAdminInput,
} from '@/lib/contract';
import { provisionCompanyAdmin } from '@/lib/api/companies';
import { useApiMutation } from '@/lib/api/hooks';
import { generatePassword } from '@/lib/auth/password';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { CredentialNotice } from '@/components/staff-credential-notice';
import { AddressPreview } from '@/components/mail/address-preview';

export function ProvisionAdminForm({
  companyId,
  mailDomain,
}: {
  companyId: string;
  mailDomain: string;
}) {
  const queryClient = useQueryClient();
  const [created, setCreated] = React.useState<{ address: string; password: string } | null>(null);
  const {
    register,
    handleSubmit,
    reset,
    setValue,
    setError,
    watch,
    formState: { errors, isSubmitting },
  } = useForm<ProvisionCompanyAdminInput>({
    resolver: zodResolver(ProvisionCompanyAdminSchema),
    defaultValues: { name: '', localPart: '', password: '' },
  });

  const localPart = watch('localPart');

  const mutation = useApiMutation(
    (body: ProvisionCompanyAdminInput) => provisionCompanyAdmin(companyId, body),
    {
      successMessage: (result) => `Company admin provisioned — ${result.admin.email}`,
      onSuccess: (result, variables) => {
        setCreated({ address: result.admin.email, password: variables.password });
        reset();
        void queryClient.invalidateQueries({ queryKey: ['company', companyId] });
        void queryClient.invalidateQueries({ queryKey: ['companies'] });
      },
      onError: (error) => {
        if (error.status === 409) {
          setError('localPart', { message: error.message });
        }
      },
    },
  );

  const onSubmit = handleSubmit((values) => mutation.mutate(values));

  return (
    <form onSubmit={onSubmit} className="space-y-4" noValidate>
      {created ? (
        <CredentialNotice
          title="Company admin provisioned"
          address={created.address}
          password={created.password}
          onDismiss={() => setCreated(null)}
        />
      ) : null}
      <div className="space-y-1.5">
        <label htmlFor="admin-name" className="text-sm font-medium">
          Admin name
        </label>
        <Input
          id="admin-name"
          placeholder="Jordan Lee"
          aria-invalid={Boolean(errors.name)}
          {...register('name')}
        />
        {errors.name ? <p className="text-xs text-destructive">{errors.name.message}</p> : null}
      </div>
      <div className="space-y-1.5">
        <label htmlFor="admin-localpart" className="text-sm font-medium">
          Mailbox Name
        </label>
        <Input
          id="admin-localpart"
          placeholder="admin"
          autoCapitalize="none"
          spellCheck={false}
          aria-invalid={Boolean(errors.localPart)}
          {...register('localPart')}
        />
        {errors.localPart ? (
          <p className="text-xs text-destructive">{errors.localPart.message}</p>
        ) : (
          <AddressPreview address={previewAddress(localPart, mailDomain)} />
        )}
      </div>
      <div className="space-y-1.5">
        <div className="flex items-center justify-between">
          <label htmlFor="admin-password" className="text-sm font-medium">
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
          id="admin-password"
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
            The admin signs in with their address + this password and can change it later.
          </p>
        )}
      </div>
      <Button type="submit" disabled={isSubmitting}>
        <UserPlus />
        {isSubmitting ? 'Provisioning…' : 'Provision admin'}
      </Button>
    </form>
  );
}
