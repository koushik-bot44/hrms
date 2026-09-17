'use client';

import * as React from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { AlertTriangle, Pencil } from 'lucide-react';
import {
  Form2Schema,
  OnboardExistingEmployeeSchema,
  OnboardingType,
  type Form2Values,
  type Form2View,
} from '@/lib/contract';
import { editEmployeeForm2 } from '@/lib/api/employees';
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
import {
  EXISTING_EMAIL_HINT,
  EmployeeInfoFields,
  form2ErrorField,
} from '@/components/employee-info/employee-info-fields';

function toValues(f: Form2View | null): Form2Values {
  const s = (v?: string | null) => v ?? '';
  return {
    fullName: s(f?.fullName),
    personalEmail: s(f?.personalEmail),
    designation: s(f?.designation),
    dateOfJoining: s(f?.dateOfJoining),
    employeeId: s(f?.employeeId),
    officialEmail: s(f?.officialEmail),
  };
}

const FORM2_RESOLVER = zodResolver(Form2Schema);
// An existing employee has already joined (§3.2): their joining date may be any past day, never a future one.
const EXISTING_RESOLVER = zodResolver(OnboardExistingEmployeeSchema) as unknown as typeof FORM2_RESOLVER;

/**
 * Edit Form 2 while the employee is INVITED (§3.2). Prefilled from the current record; a change to the
 * PERSONAL email re-sends the invite to the new address (surfaced inline). The API rejects a manual edit
 * once the employee starts (409) — the parent only renders this while INVITED, so that never fires here.
 * An EXISTING employee's record is HR-entered: editable until approved, never re-invited, and the joining date
 * is capped at today (no minimum is applied on edit).
 */
export function EditEmployeeInfoDialog({
  employeeId,
  form2,
  employeeCode,
  onboardingType,
  onSaved,
}: {
  employeeId: string;
  form2: Form2View | null;
  employeeCode?: string | null;
  /** How the record was opened; absent (an older API) behaves as a new hire. */
  onboardingType?: OnboardingType | null;
  onSaved?: () => void;
}) {
  const [open, setOpen] = React.useState(false);
  const original = (form2?.personalEmail ?? '').trim().toLowerCase();
  const existing = onboardingType === OnboardingType.EXISTING_EMPLOYEE;

  const {
    register,
    handleSubmit,
    reset,
    watch,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<Form2Values>({
    resolver: existing ? EXISTING_RESOLVER : FORM2_RESOLVER,
    defaultValues: toValues(form2),
  });

  // Re-open resets the form to the latest record values.
  React.useEffect(() => {
    if (open) reset(toValues(form2));
  }, [open, form2, reset]);

  const emailNow = (watch('personalEmail') ?? '').trim().toLowerCase();
  // An existing employee is never emailed, so an email change has no re-invite consequence to warn about.
  const emailChanged = !existing && emailNow.length > 0 && emailNow !== original;

  // Only an existing employee's ID is HR-entered; a new hire's is minted on approval, so it is never sent.
  const mutation = useApiMutation(
    (values: Form2Values) =>
      editEmployeeForm2(employeeId, existing ? values : { ...values, employeeId: undefined }),
    {
      successMessage: (_data, values) =>
        !existing && (values.personalEmail ?? '').trim().toLowerCase() !== original
          ? 'Employee Info saved — invitation re-sent to the new email'
          : 'Employee Info saved',
      onSuccess: () => {
        setOpen(false);
        onSaved?.();
      },
      onError: (error) => {
        if (error.status === 400 || error.status === 409) {
          setError(form2ErrorField(error.message), { message: error.message });
        }
      },
    },
  );

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button type="button" variant="outline" size="sm">
          <Pencil className="size-4" />
          Edit Employee Info
        </Button>
      </DialogTrigger>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>Edit Employee Info (Form 2)</DialogTitle>
          <DialogDescription>
            {existing ? (
              <>You can edit Form 2 until you approve this existing employee.</>
            ) : (
              <>
                You can edit Form 2 while the employee is still <span className="font-medium">invited</span>.
                Once they start filling their forms, it locks.
              </>
            )}
          </DialogDescription>
        </DialogHeader>
        <form onSubmit={handleSubmit((v) => mutation.mutate(v))} className="space-y-6" noValidate>
          <EmployeeInfoFields
            register={register}
            errors={errors}
            employeeCode={employeeCode}
            dojMax={existing ? istTodayIso() : undefined}
            personalEmailHint={existing ? EXISTING_EMAIL_HINT : undefined}
            enterCompanyIdentity={existing}
            idPrefix="edit-f2"
          />
          {emailChanged ? (
            <div className="flex items-start gap-2 rounded-md border border-warning/40 bg-warning/5 p-3 text-sm">
              <AlertTriangle className="mt-0.5 size-4 shrink-0 text-warning" />
              <p>
                Changing the personal email will <span className="font-medium">re-send the invitation</span>{' '}
                to the new address; the previous address can no longer sign in.
              </p>
            </div>
          ) : null}
          <div className="sticky bottom-0 -mx-6 -mb-6 flex justify-end gap-2 border-t bg-background px-6 py-4">
            <Button type="button" variant="ghost" onClick={() => setOpen(false)}>
              Cancel
            </Button>
            <Button type="submit" disabled={isSubmitting || mutation.isPending}>
              {isSubmitting || mutation.isPending ? 'Saving…' : 'Save changes'}
            </Button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}
