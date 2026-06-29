'use client';

import * as React from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useQueryClient } from '@tanstack/react-query';
import { Plus } from 'lucide-react';
import { CreateCompanySchema, type CompanySummary, type CreateCompanyInput } from '@/lib/contract';
import { createCompany } from '@/lib/api/companies';
import { useApiMutation } from '@/lib/api/hooks';
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

  const {
    register,
    handleSubmit,
    reset,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<CreateCompanyInput>({
    resolver: zodResolver(CreateCompanySchema),
    defaultValues: { name: '', code: '' },
  });

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
        status: 'ACTIVE',
        teamCount: 0,
        employeeCount: 0,
        hasAdmin: false,
        createdAt: new Date().toISOString(),
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
      reset();
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
        if (!next) reset();
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
