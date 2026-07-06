'use client';

import * as React from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useQueryClient } from '@tanstack/react-query';
import { UserPlus } from 'lucide-react';
import { ProvisionCompanyAdminSchema, type ProvisionCompanyAdminInput } from '@/lib/contract';
import { provisionCompanyAdmin } from '@/lib/api/companies';
import { useApiMutation } from '@/lib/api/hooks';
import { generatePassword } from '@/lib/auth/password';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { CredentialNotice } from '@/components/staff-credential-notice';

export function ProvisionAdminForm({ companyId }: { companyId: string }) {
  const queryClient = useQueryClient();
  const [created, setCreated] = React.useState<{ email: string; password: string } | null>(null);
  const {
    register,
    handleSubmit,
    reset,
    setValue,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<ProvisionCompanyAdminInput>({
    resolver: zodResolver(ProvisionCompanyAdminSchema),
    defaultValues: { name: '', email: '', password: '' },
  });

  const mutation = useApiMutation(
    (body: ProvisionCompanyAdminInput) => provisionCompanyAdmin(companyId, body),
    {
      successMessage: 'Company admin provisioned',
      onSuccess: (_result, variables) => {
        setCreated({ email: variables.email, password: variables.password });
        reset();
        void queryClient.invalidateQueries({ queryKey: ['company', companyId] });
        void queryClient.invalidateQueries({ queryKey: ['companies'] });
      },
      onError: (error) => {
        if (error.status === 409) {
          setError('email', { message: error.message });
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
          email={created.email}
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
        <label htmlFor="admin-email" className="text-sm font-medium">
          Admin email
        </label>
        <Input
          id="admin-email"
          type="email"
          placeholder="admin@company.com"
          aria-invalid={Boolean(errors.email)}
          {...register('email')}
        />
        {errors.email ? <p className="text-xs text-destructive">{errors.email.message}</p> : null}
      </div>
      <div className="space-y-1.5">
        <div className="flex items-center justify-between">
          <label htmlFor="admin-password" className="text-sm font-medium">
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
            The admin signs in with their email + this password and can change it later.
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
