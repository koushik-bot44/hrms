'use client';

import * as React from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { CheckCircle2, Copy, UserPlus } from 'lucide-react';
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
    defaultValues: { email: '' },
  });

  const mutation = useApiMutation((body: OnboardEmployeeInput) => onboardEmployee(body), {
    successMessage: (data) => `Onboarded — ${data.employee.employeeCode}`,
    // Optimistic insert with a placeholder code, replaced when the list refetches.
    onMutate: async (vars): Promise<OptimisticContext> => {
      await queryClient.cancelQueries({ queryKey: EMPLOYEES_KEY });
      const prev = queryClient.getQueryData<EmployeeSummary[]>(EMPLOYEES_KEY);
      const optimistic: EmployeeSummary = {
        id: `optimistic-${vars.email}`,
        employeeCode: '…',
        email: vars.email,
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
      if (error.status === 400) {
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

  const copyCode = () => {
    if (result) {
      void navigator.clipboard?.writeText(result.employee.employeeCode);
      toast.success('Employee ID copied');
    }
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
                We emailed the unique ID and a login link to{' '}
                <span className="font-medium text-foreground">{result.employee.email}</span>.
              </DialogDescription>
            </DialogHeader>
            <div className="space-y-1.5">
              <span className="text-sm font-medium">Employee ID</span>
              <div className="flex items-center justify-between gap-2 rounded-md border bg-muted/40 px-3 py-2">
                <span className="font-mono text-sm">{result.employee.employeeCode}</span>
                <Button type="button" variant="ghost" size="icon" onClick={copyCode} aria-label="Copy">
                  <Copy className="size-4" />
                </Button>
              </div>
            </div>
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
                Enter the employee&apos;s email. We&apos;ll mint their unique ID and email it with a
                login link.
              </DialogDescription>
            </DialogHeader>
            <form onSubmit={onSubmit} className="space-y-4" noValidate>
              <div className="space-y-1.5">
                <label htmlFor="employee-email" className="text-sm font-medium">
                  Employee email
                </label>
                <Input
                  id="employee-email"
                  type="email"
                  placeholder="new.hire@personal.com"
                  aria-invalid={Boolean(errors.email)}
                  {...register('email')}
                />
                {errors.email ? (
                  <p className="text-xs text-destructive">{errors.email.message}</p>
                ) : null}
              </div>
              <div className="flex justify-end gap-2 pt-2">
                <Button type="button" variant="ghost" onClick={() => setOpen(false)}>
                  Cancel
                </Button>
                <Button type="submit" disabled={isSubmitting}>
                  {isSubmitting ? 'Onboarding…' : 'Onboard & email ID'}
                </Button>
              </div>
            </form>
          </>
        )}
      </DialogContent>
    </Dialog>
  );
}
