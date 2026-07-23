'use client';

import * as React from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { AlertTriangle, Pencil } from 'lucide-react';
import { Form2Schema, type Form2Values, type Form2View } from '@/lib/contract';
import { editEmployeeForm2 } from '@/lib/api/employees';
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
import { EmployeeInfoFields } from '@/components/employee-info/employee-info-fields';

function toValues(f: Form2View | null): Form2Values {
  const s = (v?: string | null) => v ?? '';
  return {
    fullName: s(f?.fullName),
    personalEmail: s(f?.personalEmail),
    designation: s(f?.designation),
    dateOfJoining: s(f?.dateOfJoining),
    officialEmail: s(f?.officialEmail),
  };
}

/**
 * Edit Form 2 while the employee is INVITED (§3.2). Prefilled from the current record; a change to the
 * PERSONAL email re-sends the invite to the new address (surfaced inline). The API rejects a manual edit
 * once the employee starts (409) — the parent only renders this while INVITED, so that never fires here.
 */
export function EditEmployeeInfoDialog({
  employeeId,
  form2,
  employeeCode,
  onSaved,
}: {
  employeeId: string;
  form2: Form2View | null;
  employeeCode?: string | null;
  onSaved?: () => void;
}) {
  const [open, setOpen] = React.useState(false);
  const original = (form2?.personalEmail ?? '').trim().toLowerCase();

  const {
    register,
    handleSubmit,
    reset,
    watch,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<Form2Values>({
    resolver: zodResolver(Form2Schema),
    defaultValues: toValues(form2),
  });

  // Re-open resets the form to the latest record values.
  React.useEffect(() => {
    if (open) reset(toValues(form2));
  }, [open, form2, reset]);

  const emailNow = (watch('personalEmail') ?? '').trim().toLowerCase();
  const emailChanged = emailNow.length > 0 && emailNow !== original;

  const mutation = useApiMutation((values: Form2Values) => editEmployeeForm2(employeeId, values), {
    successMessage: (_data, values) =>
      (values.personalEmail ?? '').trim().toLowerCase() !== original
        ? 'Employee Info saved — invitation re-sent to the new email'
        : 'Employee Info saved',
    onSuccess: () => {
      setOpen(false);
      onSaved?.();
    },
    onError: (error) => {
      if (error.status === 400 || error.status === 409) {
        setError('personalEmail', { message: error.message });
      }
    },
  });

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
            You can edit Form 2 while the employee is still <span className="font-medium">invited</span>.
            Once they start filling their forms, it locks.
          </DialogDescription>
        </DialogHeader>
        <form onSubmit={handleSubmit((v) => mutation.mutate(v))} className="space-y-6" noValidate>
          <EmployeeInfoFields
            register={register}
            errors={errors}
            employeeCode={employeeCode}
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
          <div className="flex justify-end gap-2">
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
