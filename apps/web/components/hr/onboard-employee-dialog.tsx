'use client';

import * as React from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useRouter } from 'next/navigation';
import { useQueryClient } from '@tanstack/react-query';
import { CheckCircle2, UserPlus } from 'lucide-react';
import {
  OnboardEmployeeSchema,
  OnboardExistingEmployeeSchema,
  OnboardingType,
  type OnboardEmployeeInput,
  type OnboardEmployeeResult,
} from '@/lib/contract';
import { onboardEmployee, onboardExistingEmployee } from '@/lib/api/employees';
import { useApiMutation } from '@/lib/api/hooks';
import { useCompanyPath } from '@/lib/auth/use-company-path';
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
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import {
  EXISTING_EMAIL_HINT,
  EmployeeInfoFields,
  form2ErrorField,
} from '@/components/employee-info/employee-info-fields';
import { OfferTermsFields } from '@/components/employee-info/offer-terms-fields';

const EMPLOYEES_KEY = ['hr-employees'] as const;

export const EMPTY_FORM2: OnboardEmployeeInput = {
  fullName: '',
  personalEmail: '',
  designation: '',
  dateOfJoining: '',
  employeeId: '',
  officialEmail: '',
  salary: '', // no pre-fill — salary is required (zod min(1) + server @NotBlank); the field shows a placeholder
  location: 'Hyderabad',
};

// One form serves both modes — the Form-2 fields are shared, so switching keeps what HR typed. The resolver
// follows the mode (RHF applies the latest resolver on every render), so existing mode never requires salary.
const NEW_HIRE_RESOLVER = zodResolver(OnboardEmployeeSchema);
const EXISTING_RESOLVER = zodResolver(OnboardExistingEmployeeSchema) as unknown as typeof NEW_HIRE_RESOLVER;

/**
 * HR onboarding = fill FORM 2 — Employee Info (§3.2). A NEW hire: submitting creates the record + the offer letter
 * and emails the invite. An EXISTING employee (already works here, no record yet): submitting only creates the
 * record — no offer, no email; HR then enters their details and documents on the record page and approves.
 */
export function OnboardEmployeeDialog({ trigger }: { trigger?: React.ReactNode }) {
  const [open, setOpen] = React.useState(false);
  const [mode, setMode] = React.useState<OnboardingType>(OnboardingType.NEW_HIRE);
  const [result, setResult] = React.useState<{ data: OnboardEmployeeResult; mode: OnboardingType } | null>(
    null,
  );
  const queryClient = useQueryClient();
  const router = useRouter();
  const cp = useCompanyPath();
  const existing = mode === OnboardingType.EXISTING_EMPLOYEE;

  const {
    register,
    handleSubmit,
    reset,
    getValues,
    setValue,
    setError,
    clearErrors,
    trigger: revalidate,
    formState: { errors, isSubmitting, isSubmitted },
  } = useForm<OnboardEmployeeInput>({
    resolver: existing ? EXISTING_RESOLVER : NEW_HIRE_RESOLVER,
    defaultValues: EMPTY_FORM2,
  });

  const mutation = useApiMutation(
    ({ mode: m, values }: { mode: OnboardingType; values: OnboardEmployeeInput }) => {
      const { salary, location, ...form2 } = values;
      return m === OnboardingType.EXISTING_EMPLOYEE
        ? onboardExistingEmployee(form2) // Form 2 incl. their existing ID — no offer terms, no offer letter (§3.2)
        : onboardEmployee({ ...form2, employeeId: undefined, salary, location }); // ID minted on approval
    },
    {
      successMessage: (data, vars) =>
        vars.mode === OnboardingType.EXISTING_EMPLOYEE
          ? `Record created for ${data.employee.fullName}`
          : `${data.employee.fullName} onboarded`,
      onError: (error) => {
        if (error.status === 400 || error.status === 409) {
          setError(form2ErrorField(error.message), { message: error.message });
        }
      },
      onSuccess: (data, vars) => {
        setResult({ data, mode: vars.mode });
        reset(EMPTY_FORM2);
      },
      onSettled: () => {
        void queryClient.invalidateQueries({ queryKey: EMPLOYEES_KEY });
        void queryClient.invalidateQueries({ queryKey: ['dashboard'] });
      },
    },
  );

  const changeMode = (next: OnboardingType) => {
    // A past joining date is valid only for an existing employee — don't carry it silently into a new hire.
    if (next === OnboardingType.NEW_HIRE) {
      const doj = getValues('dateOfJoining');
      if (doj && doj < istTodayIso()) setValue('dateOfJoining', '');
      // A new hire's ID is minted on approval and their official email is assigned later — drop what was typed.
      setValue('employeeId', '');
      setValue('officialEmail', '');
    }
    setMode(next);
  };

  // Re-check against the NEW mode's schema once its resolver is live (the render after the switch): after a
  // failed submit, still-relevant errors stay visible; before any submit, the other mode's errors just clear.
  React.useEffect(() => {
    if (isSubmitted) void revalidate();
    else clearErrors();
  }, [mode, isSubmitted, revalidate, clearErrors]);

  const close = () => {
    setOpen(false);
    setResult(null);
    setMode(OnboardingType.NEW_HIRE);
    reset(EMPTY_FORM2);
  };

  const busy = isSubmitting || mutation.isPending;

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        if (!next) {
          setResult(null);
          setMode(OnboardingType.NEW_HIRE);
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
            email={result.data.employee.email}
            onboardingType={result.mode}
            onAgain={() => setResult(null)}
            onDone={close}
            onAddDetails={() => {
              const id = result.data.employee.id;
              close();
              router.push(cp(`/hr/employees/${id}`));
            }}
          />
        ) : (
          <>
            <DialogHeader>
              <DialogTitle>Onboard an employee</DialogTitle>
              <DialogDescription>
                {existing ? (
                  <>
                    For someone who already works here but has no record yet. Fill in their Employee Info
                    (Form 2), including the employee ID and official email they already have — no offer letter
                    and no email. Next you add their details, documents and a scanned signature, then approve.
                  </>
                ) : (
                  <>
                    Fill in their Employee Info (Form 2). We&apos;ll email a selection note + login link to
                    their personal email — no ID is needed to sign in.
                  </>
                )}
              </DialogDescription>
            </DialogHeader>
            <form
              onSubmit={handleSubmit((v) => mutation.mutate({ mode, values: v }))}
              className="space-y-6"
              noValidate
            >
              <Tabs
                value={mode}
                onValueChange={(value) => changeMode(value as OnboardingType)}
                className="space-y-6"
              >
                <OnboardingModeTabsList disabled={busy} />
                <EmployeeInfoFields
                  register={register}
                  errors={errors}
                  // A new hire joins from today on; an existing employee has already joined (today at the latest).
                  dojMin={existing ? undefined : istTodayIso()}
                  dojMax={existing ? istTodayIso() : undefined}
                  personalEmailHint={existing ? EXISTING_EMAIL_HINT : undefined}
                  enterCompanyIdentity={existing}
                  idPrefix="onb"
                />
                <TabsContent value={OnboardingType.NEW_HIRE} className="mt-0" tabIndex={-1}>
                  <OfferTermsFields register={register} errors={errors} idPrefix="onb" />
                </TabsContent>
                <TabsContent value={OnboardingType.EXISTING_EMPLOYEE} className="mt-0" tabIndex={-1}>
                  <ExistingEmployeeNoOffer />
                </TabsContent>
              </Tabs>
              <div className="sticky bottom-0 -mx-6 -mb-6 flex justify-end gap-2 border-t bg-background px-6 py-4">
                <Button type="button" variant="ghost" onClick={close}>
                  Cancel
                </Button>
                <Button type="submit" disabled={busy}>
                  {existing ? (busy ? 'Creating…' : 'Create record') : busy ? 'Onboarding…' : 'Onboard & email'}
                </Button>
              </div>
            </form>
          </>
        )}
      </DialogContent>
    </Dialog>
  );
}

/**
 * The onboard dialogs' mode switch (§3.2) — shared by HR + SUPER_ADMIN; render inside a `<Tabs>` whose panels
 * are the offer terms (new hire) and {@link ExistingEmployeeNoOffer}. Labels wrap on a phone-width dialog.
 */
export function OnboardingModeTabsList({ disabled }: { disabled?: boolean }) {
  const trigger = 'whitespace-normal px-3 text-center leading-tight';
  return (
    <TabsList aria-label="Onboarding type" className="grid h-auto w-full grid-cols-2 items-stretch">
      <TabsTrigger value={OnboardingType.NEW_HIRE} disabled={disabled} className={trigger}>
        <span>
          New hire <span className="whitespace-nowrap">(offer letter)</span>
        </span>
      </TabsTrigger>
      <TabsTrigger value={OnboardingType.EXISTING_EMPLOYEE} disabled={disabled} className={trigger}>
        Existing employee
      </TabsTrigger>
    </TabsList>
  );
}

/** Where the offer terms would be, in existing-employee mode: no offer letter and no email (§3.2). */
export function ExistingEmployeeNoOffer() {
  return (
    <section className="space-y-0.5">
      <h3 className="text-sm font-semibold">No offer letter, no email</h3>
      <p className="text-xs text-muted-foreground">
        They already work here and won&apos;t sign in to onboard. After creating the record, HR enters their
        personal details, documents and a scanned signature, then approves — their existing employee ID is kept.
      </p>
    </section>
  );
}

/**
 * Shared success panel. A new hire: the invite went to their personal email. An existing employee: only the record
 * was created (no email) — `onAddDetails` (HR) jumps to entering their details; without it (SUPER_ADMIN) the
 * team's HR does that.
 */
export function OnboardedConfirmation({
  email,
  onboardingType = OnboardingType.NEW_HIRE,
  onAgain,
  onDone,
  onAddDetails,
}: {
  email: string;
  onboardingType?: OnboardingType;
  onAgain: () => void;
  onDone: () => void;
  onAddDetails?: () => void;
}) {
  const existing = onboardingType === OnboardingType.EXISTING_EMPLOYEE;
  return (
    <div className="space-y-5">
      <DialogHeader>
        <div className="mb-1 flex size-9 items-center justify-center rounded-full bg-success/10 text-success">
          <CheckCircle2 className="size-5" />
        </div>
        <DialogTitle>{existing ? 'Record created' : 'Employee onboarded'}</DialogTitle>
        <DialogDescription>
          {existing ? (
            <>
              No email was sent to <span className="font-medium text-foreground">{email}</span>.{' '}
              {onAddDetails
                ? 'Now add their personal details, documents and a scanned signature, then approve.'
                : "The team's HR now adds their personal details, documents and a scanned signature, then approves."}
            </>
          ) : (
            <>
              We emailed a selection note and a login link to{' '}
              <span className="font-medium text-foreground">{email}</span>. They sign in with their full
              name + this email; a unique employee ID is assigned once HR approves them.
            </>
          )}
        </DialogDescription>
      </DialogHeader>
      <div className="flex justify-end gap-2">
        <Button variant="outline" onClick={onAgain}>
          Onboard another
        </Button>
        {existing && onAddDetails ? (
          <Button onClick={onAddDetails}>Add their details</Button>
        ) : (
          <Button onClick={onDone}>Done</Button>
        )}
      </div>
    </div>
  );
}
