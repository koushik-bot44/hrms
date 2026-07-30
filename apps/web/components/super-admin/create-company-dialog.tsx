'use client';

import * as React from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useQueryClient } from '@tanstack/react-query';
import { Plus } from 'lucide-react';
import { CreateCompanySchema, type CompanySummary, type CreateCompanyInput } from '@/lib/contract';
import { createCompany } from '@/lib/api/companies';
import { useApiMutation } from '@/lib/api/hooks';
import { slugifyCompanyName } from '@/lib/company-url';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';

const COMPANIES_KEY = ['companies'] as const;

interface OptimisticContext {
  prev?: CompanySummary[];
}

export function CreateCompanyDialog() {
  const [open, setOpen] = React.useState(false);
  const queryClient = useQueryClient();

  // The mail domain (§8) prefills from the code (lowercased) until the admin edits it themselves.
  const [domainEdited, setDomainEdited] = React.useState(false);
  const {
    register,
    handleSubmit,
    reset,
    setError,
    setValue,
    watch,
    formState: { errors, isSubmitting },
  } = useForm<CreateCompanyInput>({
    resolver: zodResolver(CreateCompanySchema),
    defaultValues: { name: '', code: '', mailDomain: '' },
  });

  const resetForm = React.useCallback(() => {
    reset();
    setDomainEdited(false);
  }, [reset]);

  const code = watch('code');
  React.useEffect(() => {
    if (!domainEdited) {
      setValue('mailDomain', (code ?? '').trim().toLowerCase());
    }
  }, [code, domainEdited, setValue]);

  const domainField = register('mailDomain');

  const mutation = useApiMutation((body: CreateCompanyInput) => createCompany(body), {
    successMessage: (company) => `Company “${company.name}” created`,
    // Optimistic add: show the row immediately, roll back on error.
    onMutate: async (vars): Promise<OptimisticContext> => {
      await queryClient.cancelQueries({ queryKey: COMPANIES_KEY });
      const prev = queryClient.getQueryData<CompanySummary[]>(COMPANIES_KEY);
      const optimistic: CompanySummary = {
        id: `optimistic-${vars.code}`,
        name: vars.name,
        code: vars.code,
        // Placeholder only — the real permanent slug is minted server-side and arrives on refetch.
        slug: slugifyCompanyName(vars.name),
        status: 'ACTIVE',
        teamCount: 0,
        employeeCount: 0,
        hasAdmin: false,
        createdAt: new Date().toISOString(),
        deletedAt: null,
      };
      queryClient.setQueryData<CompanySummary[]>(COMPANIES_KEY, (old) =>
        old ? [optimistic, ...old] : [optimistic],
      );
      return { prev };
    },
    onError: (error, _vars, context) => {
      const ctx = context as OptimisticContext | undefined;
      if (ctx?.prev) {
        queryClient.setQueryData(COMPANIES_KEY, ctx.prev);
      }
      if (error.status === 409) {
        setError('code', { message: error.message });
      }
    },
    onSuccess: () => {
      setOpen(false);
      resetForm();
    },
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: COMPANIES_KEY });
    },
  });

  const onSubmit = handleSubmit((values) => mutation.mutate(values));

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        if (!next) resetForm();
      }}
    >
      <DialogTrigger asChild>
        <Button size="sm">
          <Plus />
          New company
        </Button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Create a company</DialogTitle>
          <DialogDescription>
            The code becomes part of every employee ID (e.g. <span className="font-mono">ACME</span>
            -EMP-000123) and cannot be reused.
          </DialogDescription>
        </DialogHeader>
        <form onSubmit={onSubmit} className="space-y-4" noValidate>
          <div className="space-y-1.5">
            <label htmlFor="company-name" className="text-sm font-medium">
              Name
            </label>
            <Input
              id="company-name"
              placeholder="Acme Corporation"
              aria-invalid={Boolean(errors.name)}
              {...register('name')}
            />
            {errors.name ? <p className="text-xs text-destructive">{errors.name.message}</p> : null}
          </div>
          <div className="space-y-1.5">
            <label htmlFor="company-code" className="text-sm font-medium">
              Code
            </label>
            <Input
              id="company-code"
              placeholder="ACME"
              autoCapitalize="characters"
              className="font-mono uppercase"
              aria-invalid={Boolean(errors.code)}
              {...register('code')}
            />
            {errors.code ? <p className="text-xs text-destructive">{errors.code.message}</p> : null}
          </div>
          <div className="space-y-1.5">
            <label htmlFor="company-mail-domain" className="text-sm font-medium">
              Mail Domain
            </label>
            <Input
              id="company-mail-domain"
              placeholder="acme"
              autoCapitalize="none"
              spellCheck={false}
              className="font-mono lowercase"
              aria-invalid={Boolean(errors.mailDomain)}
              {...domainField}
              onChange={(e) => {
                setDomainEdited(true);
                void domainField.onChange(e);
              }}
            />
            {errors.mailDomain ? (
              <p className="text-xs text-destructive">{errors.mailDomain.message}</p>
            ) : (
              <p className="text-xs text-muted-foreground">
                Used for employee/staff mailbox addresses, e.g.{' '}
                <span className="font-mono">name@{watch('mailDomain') || 'yourdomain'}</span> — unique
                across companies, and cannot be changed later.
              </p>
            )}
          </div>
          <div className="flex justify-end gap-2 pt-2">
            <Button type="button" variant="ghost" onClick={() => setOpen(false)}>
              Cancel
            </Button>
            <Button type="submit" disabled={isSubmitting}>
              {isSubmitting ? 'Creating…' : 'Create company'}
            </Button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}
