'use client';

import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { UserPlus } from 'lucide-react';
import { ProvisionCompanyAdminSchema, type ProvisionCompanyAdminInput } from '@/lib/contract';
import { provisionCompanyAdmin } from '@/lib/api/companies';
import { useApiMutation } from '@/lib/api/hooks';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';

export function ProvisionAdminForm({ companyId }: { companyId: string }) {
  const queryClient = useQueryClient();
  const {
    register,
    handleSubmit,
    reset,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<ProvisionCompanyAdminInput>({
    resolver: zodResolver(ProvisionCompanyAdminSchema),
    defaultValues: { name: '', email: '' },
  });

  const mutation = useApiMutation(
    (body: ProvisionCompanyAdminInput) => provisionCompanyAdmin(companyId, body),
    {
      successMessage: 'Company admin provisioned',
      onSuccess: (result) => {
        reset();
        void queryClient.invalidateQueries({ queryKey: ['company', companyId] });
        void queryClient.invalidateQueries({ queryKey: ['companies'] });
        if (result.devPassword) {
          toast.message('Temporary password (dev only)', {
            description: `${result.admin.email} · ${result.devPassword}`,
          });
        }
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
        <p className="text-xs text-muted-foreground">
          Initial credentials are emailed to the admin (logged to the server in dev).
        </p>
      </div>
      <Button type="submit" disabled={isSubmitting}>
        <UserPlus />
        {isSubmitting ? 'Provisioning…' : 'Provision admin'}
      </Button>
    </form>
  );
}
