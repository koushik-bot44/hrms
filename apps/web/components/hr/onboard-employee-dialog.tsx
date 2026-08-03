'use client';

import * as React from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useQueryClient } from '@tanstack/react-query';
import { CheckCircle2, UserPlus } from 'lucide-react';
import {
  OnboardEmployeeSchema,
  type OnboardEmployeeInput,
  type OnboardEmployeeResult,
} from '@/lib/contract';
import { onboardEmployee } from '@/lib/api/employees';
import { useApiMutation } from '@/lib/api/hooks';
import { istTodayIso } from '@/lib/date';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog';
import { EmployeeInfoFields } from '@/components/employee-info/employee-info-fields';

const EMPLOYEES_KEY = ['hr-employees'] as const;

export const EMPTY_FORM2: OnboardEmployeeInput = {
  fullName: '',
  personalEmail: '',
  designation: '',
  dateOfJoining: '',
  officialEmail: '',
};

/** HR onboarding = fill FORM 2 — Employee Info (§3.2). Submitting it creates the record + sends the invite. */
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
    defaultValues: EMPTY_FORM2,
  });

  const mutation = useApiMutation((body: OnboardEmployeeInput) => onboardEmployee(body), {
    successMessage: (data) => `${data.employee.fullName} onboarded`,
    onError: (error) => {
      if (error.status === 400 || error.status === 409) {
        setError('personalEmail', { message: error.message });
      }
    },
    onSuccess: (data) => {
      setResult(data);
      reset(EMPTY_FORM2);
    },
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: EMPLOYEES_KEY });
      void queryClient.invalidateQueries({ queryKey: ['dashboard'] });
    },
  });

  const close = () => {
    setOpen(false);
    setResult(null);
    reset(EMPTY_FORM2);
  };

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        if (!next) {
          setResult(null);
          reset(EMPTY_FORM2);
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
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-2xl">
        {result ? (
          <OnboardedConfirmation
            email={result.employee.email}
            onAgain={() => setResult(null)}
            onDone={close}
          />
        ) : (
          <>
            <DialogHeader>
              <DialogTitle>Onboard an employee</DialogTitle>
              <DialogDescription>
                Fill in their Employee Info (Form 2). We&apos;ll email a selection note + login link to
                their personal email — no ID is needed to sign in.
              </DialogDescription>
            </DialogHeader>
            <form onSubmit={handleSubmit((v) => mutation.mutate(v))} className="space-y-6" noValidate>
              <EmployeeInfoFields
                register={register}
                errors={errors}
                dojMin={istTodayIso()}
                idPrefix="onb"
              />
              <div className="sticky bottom-0 -mx-6 -mb-6 flex justify-end gap-2 border-t bg-background px-6 py-4">
                <Button type="button" variant="ghost" onClick={() => setOpen(false)}>
                  Cancel
                </Button>
                <Button type="submit" disabled={isSubmitting || mutation.isPending}>
                  {isSubmitting || mutation.isPending ? 'Onboarding…' : 'Onboard & email'}
                </Button>
              </div>
            </form>
          </>
        )}
      </DialogContent>
    </Dialog>
  );
}

/** Shared success panel — the invite went to the employee's personal email. */
export function OnboardedConfirmation({
  email,
  onAgain,
  onDone,
}: {
  email: string;
  onAgain: () => void;
  onDone: () => void;
}) {
  return (
    <div className="space-y-5">
      <DialogHeader>
        <div className="mb-1 flex size-9 items-center justify-center rounded-full bg-success/10 text-success">
          <CheckCircle2 className="size-5" />
        </div>
        <DialogTitle>Employee onboarded</DialogTitle>
        <DialogDescription>
          We emailed a selection note and a login link to{' '}
          <span className="font-medium text-foreground">{email}</span>. They sign in with their full
          name + this email; a unique employee ID is assigned once HR approves them.
        </DialogDescription>
      </DialogHeader>
      <div className="flex justify-end gap-2">
        <Button variant="outline" onClick={onAgain}>
          Onboard another
        </Button>
        <Button onClick={onDone}>Done</Button>
      </div>
    </div>
  );
}
