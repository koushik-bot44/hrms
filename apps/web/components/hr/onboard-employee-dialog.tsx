'use client';

import * as React from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useQueryClient } from '@tanstack/react-query';
import { CheckCircle2, UserPlus } from 'lucide-react';
import {
  OnboardEmployeeSchema,
  type EmployeeSummary,
  type OnboardEmployeeInput,
  type OnboardEmployeeResult,
} from '@/lib/contract';
import { onboardEmployee } from '@/lib/api/employees';
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

const EMPLOYEES_KEY = ['hr-employees'] as const;

interface OptimisticContext {
  prev?: EmployeeSummary[];
}

export function OnboardEmployeeDialog({ trigger }: { trigger?: React.ReactNode }) {
  const [open, setOpen] = React.useState(false);
  const [result, setResult] = React.useState<OnboardEmployeeResult | null>(null);
  const queryClient = useQueryClient();

  const {
    register,
    handleSubmit,
    reset,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<OnboardEmployeeInput>({
    resolver: zodResolver(OnboardEmployeeSchema),
    defaultValues: { fullName: '', email: '', designation: '', dateOfJoining: '' },
  });

  const mutation = useApiMutation((body: OnboardEmployeeInput) => onboardEmployee(body), {
    successMessage: (data) => `${data.employee.fullName} onboarded`,
    // Optimistic insert (no ID yet — it's assigned on approval), replaced when the list refetches.
    onMutate: async (vars): Promise<OptimisticContext> => {
      await queryClient.cancelQueries({ queryKey: EMPLOYEES_KEY });
      const prev = queryClient.getQueryData<EmployeeSummary[]>(EMPLOYEES_KEY);
      const optimistic: EmployeeSummary = {
        id: `optimistic-${vars.email}`,
        employeeCode: null,
        fullName: vars.fullName,
        email: vars.email,
        designation: vars.designation,
        dateOfJoining: vars.dateOfJoining,
        status: 'INVITED',
        createdAt: new Date().toISOString(),
      };
      queryClient.setQueryData<EmployeeSummary[]>(EMPLOYEES_KEY, (old) =>
        old ? [optimistic, ...old] : [optimistic],
      );
      return { prev };
    },
    onError: (error, _vars, context) => {
      const ctx = context as OptimisticContext | undefined;
      if (ctx?.prev) {
        queryClient.setQueryData(EMPLOYEES_KEY, ctx.prev);
      }
      if (error.status === 400 || error.status === 409) {
        setError('email', { message: error.message });
      }
    },
    onSuccess: (data) => {
      setResult(data);
      reset();
    },
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: EMPLOYEES_KEY });
    },
  });

  const onSubmit = handleSubmit((values) => mutation.mutate(values));

  const close = () => {
    setOpen(false);
    setResult(null);
    reset();
  };

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        if (!next) {
          setResult(null);
          reset();
        }
      }}
    >
      <DialogTrigger asChild>
        {trigger ?? (
          <Button size="sm">
            <UserPlus />
            Onboard employee
          </Button>
        )}
      </DialogTrigger>
      <DialogContent>
        {result ? (
          <div className="space-y-5">
            <DialogHeader>
              <div className="mb-1 flex size-9 items-center justify-center rounded-full bg-success/10 text-success">
                <CheckCircle2 className="size-5" />
              </div>
              <DialogTitle>Employee onboarded</DialogTitle>
              <DialogDescription>
                We emailed a selection note and a login link to{' '}
                <span className="font-medium text-foreground">{result.employee.email}</span>. They
                sign in with their full name + email; a unique employee ID is assigned once a Manager
                approves them.
              </DialogDescription>
            </DialogHeader>
            <div className="flex justify-end gap-2">
              <Button variant="outline" onClick={() => setResult(null)}>
                Onboard another
              </Button>
              <Button onClick={close}>Done</Button>
            </div>
          </div>
        ) : (
          <>
            <DialogHeader>
              <DialogTitle>Onboard an employee</DialogTitle>
              <DialogDescription>
                Enter their details. We&apos;ll email a selection note with a login link — no ID is
                needed to sign in.
              </DialogDescription>
            </DialogHeader>
            <form onSubmit={onSubmit} className="space-y-4" noValidate>
              <Field id="onb-name" label="Full name" error={errors.fullName?.message}>
                <Input
                  id="onb-name"
                  placeholder="Alex Doe"
                  aria-invalid={Boolean(errors.fullName)}
                  {...register('fullName')}
                />
              </Field>
              <Field id="onb-email" label="Email" error={errors.email?.message}>
                <Input
                  id="onb-email"
                  type="email"
                  placeholder="new.hire@personal.com"
                  aria-invalid={Boolean(errors.email)}
                  {...register('email')}
                />
              </Field>
              <Field id="onb-designation" label="Designation" error={errors.designation?.message}>
                <Input
                  id="onb-designation"
                  placeholder="Software Engineer"
                  aria-invalid={Boolean(errors.designation)}
                  {...register('designation')}
                />
              </Field>
              <Field id="onb-doj" label="Date of joining" error={errors.dateOfJoining?.message}>
                <Input
                  id="onb-doj"
                  type="date"
                  aria-invalid={Boolean(errors.dateOfJoining)}
                  {...register('dateOfJoining')}
                />
              </Field>
              <div className="flex justify-end gap-2 pt-2">
                <Button type="button" variant="ghost" onClick={() => setOpen(false)}>
                  Cancel
                </Button>
                <Button type="submit" disabled={isSubmitting}>
                  {isSubmitting ? 'Onboarding…' : 'Onboard & email'}
                </Button>
              </div>
            </form>
          </>
        )}
      </DialogContent>
    </Dialog>
  );
}

function Field({
  id,
  label,
  error,
  children,
}: {
  id: string;
  label: string;
  error?: string;
  children: React.ReactNode;
}) {
  return (
    <div className="space-y-1.5">
      <label htmlFor={id} className="text-sm font-medium">
        {label}
      </label>
      {children}
      {error ? <p className="text-xs text-destructive">{error}</p> : null}
    </div>
  );
}
