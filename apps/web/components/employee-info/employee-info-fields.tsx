'use client';

import * as React from 'react';
import { type FieldErrors, type FieldValues, type Path, type UseFormRegister } from 'react-hook-form';
import { Input } from '@/components/ui/input';

/**
 * The shared FORM 2 — Employee Info field set (§3.2), authored by HR/SA at onboard and editable while
 * the employee is INVITED. Grouped into sections for scanability. `employeeId` is greyed (auto-assigned
 * on Manager approval); `sparkId` + `officialEmail` are assigned-later / inert (rendered but never
 * required and never block submit). The PERSONAL email is the employee's sign-in (OTP) identity and the
 * invite target — labelled as such. Generic over the form type so onboard (Form 2 only) and the SA form
 * (Form 2 + company/team) can both drive it.
 */
// Father's name, date of birth, blood group and mobile were removed from Form 2 (§3.2); they remain on
// Form 1. Spark ID (formerly an inert display field) was removed too.
const FORM2_FIELDS = [
  'fullName',
  'personalEmail',
  'designation',
  'dateOfJoining',
  'employeeId',
  'officialEmail',
] as const;
type Form2FieldName = (typeof FORM2_FIELDS)[number];

/**
 * Where a Form-2 onboard/edit 400/409 lands: a joining-date rejection (e.g. an existing employee's date in the
 * future) on the date field, an employee-ID rejection (e.g. already in use) on the ID, anything else — duplicate
 * or invalid email — on the personal email.
 */
export function form2ErrorField(
  message: string,
): 'dateOfJoining' | 'employeeId' | 'officialEmail' | 'personalEmail' {
  if (/joining/i.test(message)) return 'dateOfJoining';
  if (/employee\s*id/i.test(message)) return 'employeeId';
  if (/official\s*email/i.test(message)) return 'officialEmail';
  return 'personalEmail';
}

/** The personal-email hint for an EXISTING employee — their record is HR-entered and nothing is sent (§3.2). */
export const EXISTING_EMAIL_HINT =
  'Nothing is sent now. If you later assign them a mailbox, its sign-in details go to this address.';

export function EmployeeInfoFields<T extends FieldValues>({
  register,
  errors,
  employeeCode,
  dojMin,
  dojMax,
  personalEmailHint,
  enterCompanyIdentity = false,
  disabled,
  idPrefix = 'f2',
}: {
  register: UseFormRegister<T>;
  errors: FieldErrors<T>;
  /** Shown in the greyed Employee ID box: the minted code once approved, else a placeholder. */
  employeeCode?: string | null;
  /** Optional min date for Date of Joining (onboard uses today). */
  dojMin?: string;
  /** Optional max date for Date of Joining (an existing employee has already joined — today at the latest). */
  dojMax?: string;
  /** Replace the personal-email hint (an existing employee is never emailed). */
  personalEmailHint?: string;
  /**
   * An EXISTING employee (§3.2): HR enters the employee ID + official email they already have (required, kept
   * on approval). Otherwise both are system-assigned and shown greyed.
   */
  enterCompanyIdentity?: boolean;
  disabled?: boolean;
  idPrefix?: string;
}) {
  const reg = (name: Form2FieldName) => register(name as Path<T>);
  const err = (name: Form2FieldName) =>
    (errors as FieldErrors)[name]?.message as string | undefined;
  const id = (name: string) => `${idPrefix}-${name}`;

  return (
    <div className="space-y-6">
      <Section title="Identity & role">
        <Field id={id('fullName')} label="Full Name" error={err('fullName')} required>
          <Input id={id('fullName')} placeholder="Alex Doe" disabled={disabled}
            aria-invalid={Boolean(err('fullName'))} {...reg('fullName')} />
        </Field>
        <Field
          id={id('personalEmail')}
          label="Personal Email"
          error={err('personalEmail')}
          required
          hint={
            personalEmailHint ??
            'The employee signs in with their name + this email (via OTP); the invitation is sent here.'
          }
        >
          <Input id={id('personalEmail')} type="email" placeholder="alex@personal.com" disabled={disabled}
            aria-invalid={Boolean(err('personalEmail'))} {...reg('personalEmail')} />
        </Field>
        <Field id={id('designation')} label="Designation" error={err('designation')} required>
          <Input id={id('designation')} placeholder="Software Engineer" disabled={disabled}
            aria-invalid={Boolean(err('designation'))} {...reg('designation')} />
        </Field>
        <Field id={id('dateOfJoining')} label="Date of Joining" error={err('dateOfJoining')} required>
          <Input id={id('dateOfJoining')} type="date" min={dojMin} max={dojMax} disabled={disabled}
            aria-invalid={Boolean(err('dateOfJoining'))} {...reg('dateOfJoining')} />
        </Field>
      </Section>

      {/* Distinct keys: the entered (uncontrolled) and system-assigned (controlled, read-only) inputs must not be
          reused across a mode switch — React would warn about a controlled input becoming uncontrolled. */}
      {enterCompanyIdentity ? (
        <Section
          key="company-identity"
          title="Company ID & email"
          description="They already work here — enter the employee ID and official email they already have."
        >
          <Field id={id('employeeId')} label="Employee ID" error={err('employeeId')} required>
            <Input id={id('employeeId')} placeholder="EMP-1042" disabled={disabled}
              aria-invalid={Boolean(err('employeeId'))} {...reg('employeeId')} />
          </Field>
          <Field id={id('officialEmail')} label="Official Email" error={err('officialEmail')} required>
            <Input id={id('officialEmail')} type="email" placeholder="alex@company.com" disabled={disabled}
              aria-invalid={Boolean(err('officialEmail'))} {...reg('officialEmail')} />
          </Field>
        </Section>
      ) : (
        <Section
          key="system-assigned"
          title="System-assigned"
          description="Filled in automatically — not entered here."
        >
          <Field id={id('employeeId')} label="Employee ID">
            <Input
              id={id('employeeId')}
              value={employeeCode ?? ''}
              placeholder="Auto-assigned on approval"
              readOnly
              disabled
            />
          </Field>
          <Field id={id('officialEmail')} label="Official Email" hint="Assigned later.">
            <Input id={id('officialEmail')} type="email" value="" placeholder="Assigned later" readOnly disabled />
          </Field>
        </Section>
      )}
    </div>
  );
}

function Section({
  title,
  description,
  children,
}: {
  title: string;
  description?: string;
  children: React.ReactNode;
}) {
  return (
    <section className="space-y-3">
      <div className="space-y-0.5">
        <h3 className="text-sm font-semibold">{title}</h3>
        {description ? <p className="text-xs text-muted-foreground">{description}</p> : null}
      </div>
      <div className="grid gap-4 sm:grid-cols-2">{children}</div>
    </section>
  );
}

function Field({
  id,
  label,
  error,
  required,
  hint,
  children,
}: {
  id: string;
  label: string;
  error?: string;
  required?: boolean;
  hint?: string;
  children: React.ReactNode;
}) {
  return (
    <div className="space-y-1.5">
      <label htmlFor={id} className="text-sm font-medium">
        {label}
        {required ? <span className="ml-0.5 text-destructive">*</span> : null}
      </label>
      {children}
      {hint && !error ? <p className="text-xs text-muted-foreground">{hint}</p> : null}
      {error ? <p className="text-xs text-destructive">{error}</p> : null}
    </div>
  );
}
