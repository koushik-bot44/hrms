'use client';

import * as React from 'react';
import { useForm, useFieldArray, type Control, type FieldErrors, type UseFormRegister } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useQueryClient } from '@tanstack/react-query';
import { ArrowLeft, ArrowRight, Check, Plus, Send, Trash2 } from 'lucide-react';
import {
  EDUCATION_SLOTS,
  EMPLOYMENT_GROUPS,
  EMPLOYMENT_SLOTS,
  DECLARATION_TEXT,
  Form1Schema,
  Form3Schema,
  IDENTITY_SLOTS,
  DOCUMENT_TYPE_LABELS,
  evaluateOnboarding,
  type Form1Values,
  type Form1View,
  type Form3Values,
  type OnboardingDashboard,
} from '@/lib/contract';
import { submitOnboarding } from '@/lib/api/onboarding';
import { useApiMutation } from '@/lib/api/hooks';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { cn } from '@/lib/utils';
import { surface } from '@/components/ui/surface';
import { DocumentUploader } from '@/components/employee/document-uploader';
import { LazySignatureCapture } from '@/components/signature/lazy-signature-capture';
import { useOnboardingTarget } from '@/components/employee/onboarding-target';

// Form 2 (Employee Info) is HR/SA-authored at onboard (§3.2) — it is NOT a step the employee fills.
const STEPS = ['Personal', 'Prev. Employment', 'Documents', 'Review', 'Sign & Submit'];
const s = (v?: string | null) => v ?? '';

export function OnboardingStepper({
  dashboard,
  disabled,
}: {
  dashboard: OnboardingDashboard;
  disabled: boolean;
}) {
  const queryClient = useQueryClient();
  const [step, setStep] = React.useState(0);
  const refetch = () => queryClient.invalidateQueries({ queryKey: ['onboarding'] });
  const goNext = () => setStep((n) => Math.min(n + 1, STEPS.length - 1));
  const goBack = () => setStep((n) => Math.max(n - 1, 0));

  return (
    <div className="space-y-5">
      <Stepper
        steps={STEPS}
        step={step}
        onStep={setStep}
        done={[
          Boolean(dashboard.form1?.name),
          dashboard.form3.length > 0,
          dashboard.documents.length > 0,
          false,
          Boolean(dashboard.signature),
        ]}
      />
      {step === 0 && (
        <Form1Step form1={dashboard.form1} disabled={disabled} onSaved={refetch} onNext={goNext} />
      )}
      {step === 1 && (
        <Form3Step form3={dashboard.form3} disabled={disabled} onSaved={refetch} onNext={goNext} onBack={goBack} />
      )}
      {step === 2 && <Form4Step dashboard={dashboard} disabled={disabled} onNext={goNext} onBack={goBack} />}
      {step === 3 && <ReviewStep dashboard={dashboard} onNext={goNext} onBack={goBack} />}
      {step === 4 && (
        <SignStep dashboard={dashboard} disabled={disabled} onSaved={refetch} onBack={goBack} />
      )}
    </div>
  );
}

/** The step chips: click to jump; `done[i]` shows a check. Shared with HR's existing-employee entry. */
export function Stepper({
  steps,
  step,
  onStep,
  done,
}: {
  steps: readonly string[];
  step: number;
  onStep: (n: number) => void;
  done: boolean[];
}) {
  return (
    <div className="flex flex-wrap gap-2.5">
      {steps.map((label, i) => (
        <button
          key={label}
          type="button"
          onClick={() => onStep(i)}
          className={cn(
            'flex min-h-11 items-center gap-2 rounded-full border px-3.5 py-2 text-xs font-medium transition-colors',
            i === step
              ? 'border-primary/40 bg-surface-tint text-primary'
              : done[i]
                ? 'border-primary/30 text-primary hover:border-primary/50'
                : 'border-border text-muted-foreground hover:border-primary/50',
          )}
        >
          <span
            className={cn(
              'flex size-5 items-center justify-center rounded-full text-[11px]',
              i === step
                ? 'bg-primary text-primary-foreground'
                : done[i]
                  ? 'bg-primary/15 text-primary'
                  : 'bg-muted text-muted-foreground',
            )}
          >
            {done[i] ? <Check className="size-3" /> : i + 1}
          </span>
          {label}
        </button>
      ))}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Shared field helpers
// ---------------------------------------------------------------------------

function Field({ label, error, children }: { label: string; error?: string; children: React.ReactNode }) {
  return (
    <div className="space-y-1.5">
      <label className="text-sm font-medium">{label}</label>
      {children}
      {error ? <p className="text-xs text-destructive">{error}</p> : null}
    </div>
  );
}

function StepActions({
  onBack,
  onNext,
  nextLabel = 'Save & continue',
  saving,
}: {
  onBack?: () => void;
  onNext?: () => void;
  nextLabel?: string;
  saving?: boolean;
}) {
  return (
    <div className="flex justify-between pt-2">
      {onBack ? (
        <Button type="button" variant="ghost" onClick={onBack}>
          <ArrowLeft className="size-4" />
          Back
        </Button>
      ) : (
        <span />
      )}
      <Button type={onNext ? 'button' : 'submit'} onClick={onNext} disabled={saving}>
        {saving ? 'Saving…' : nextLabel}
        <ArrowRight className="size-4" />
      </Button>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Form 1 — Personal Details
// ---------------------------------------------------------------------------

function form1Defaults(f: Form1View | null): Form1Values {
  const refs = (f?.characterReferences ?? []).map((r) => ({
    name: s(r.name),
    address: s(r.address),
    phone: s(r.phone),
  }));
  while (refs.length < 2) refs.push({ name: '', address: '', phone: '' });
  return {
    name: s(f?.name),
    dateOfBirth: s(f?.dateOfBirth),
    email: s(f?.email),
    mobile: s(f?.mobile),
    currentAddress: s(f?.currentAddress),
    permanentAddress: s(f?.permanentAddress),
    alternateNumber: s(f?.alternateNumber),
    vehicleNo2W4W: s(f?.vehicleNo2W4W),
    panNumber: s(f?.panNumber),
    axisAccountNumber: s(f?.axisAccountNumber),
    maritalStatus: s(f?.maritalStatus),
    bloodGroup: s(f?.bloodGroup),
    closestRelativeName: s(f?.closestRelativeName),
    city: s(f?.city),
    declaration: s(f?.declaration),
    educationalQualifications: (f?.educationalQualifications ?? []).map((e) => ({
      qualification: s(e.qualification),
      university: s(e.university),
      yearOfPassing: s(e.yearOfPassing),
      percentage: s(e.percentage),
    })),
    familyDetails: (f?.familyDetails ?? []).map((x) => ({
      name: s(x.name),
      age: s(x.age),
      relation: s(x.relation),
      occupation: s(x.occupation),
    })),
    characterReferences: refs,
  };
}

export function Form1Step({
  form1,
  disabled,
  onSaved,
  onNext,
  submitLabel,
  declarationLabel = 'I have read and confirm the declaration above.',
}: {
  form1: Form1View | null;
  disabled: boolean;
  onSaved: () => void;
  onNext?: () => void;
  submitLabel?: string;
  /** The declaration checkbox label — HR confirming an existing employee's signed declaration words it differently. */
  declarationLabel?: string;
}) {
  const queryClient = useQueryClient();
  const { register, control, handleSubmit, watch, setValue, formState } = useForm<Form1Values>({
    resolver: zodResolver(Form1Schema),
    defaultValues: form1Defaults(form1),
  });
  const declarationAccepted = (watch('declaration') ?? '').trim().length > 0;
  const errors = formState.errors;
  const edu = useFieldArray({ control, name: 'educationalQualifications' });
  const fam = useFieldArray({ control, name: 'familyDetails' });
  const refs = useFieldArray({ control, name: 'characterReferences' });

  const target = useOnboardingTarget();
  const save = useApiMutation((v: Form1Values) => target.saveForm1(v), {
    successMessage: 'Form 1 saved',
    onSuccess: (saved) => {
      // Cache the saved view now so stepping back before the refetch lands never shows (and re-saves) old values.
      queryClient.setQueryData<OnboardingDashboard>(target.queryKey, (d) => (d ? { ...d, form1: saved } : d));
      onSaved();
      onNext?.();
    },
  });

  return (
    <form onSubmit={handleSubmit((v) => save.mutate(v))} noValidate>
      <Card>
        <CardHeader>
          <CardTitle className="text-base">Form 1 — Personal Details</CardTitle>
        </CardHeader>
        <CardContent className="space-y-5">
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Name" error={errors.name?.message}>
              <Input {...register('name')} disabled={disabled} aria-invalid={!!errors.name} />
            </Field>
            <Field label="Date of Birth" error={errors.dateOfBirth?.message}>
              <Input type="date" {...register('dateOfBirth')} disabled={disabled} />
            </Field>
            <Field label="Email"><Input type="email" {...register('email')} disabled={disabled} /></Field>
            <Field label="Mobile"><Input {...register('mobile')} disabled={disabled} /></Field>
            <Field label="Marital Status"><Input {...register('maritalStatus')} disabled={disabled} /></Field>
            <Field label="Blood Group"><Input {...register('bloodGroup')} disabled={disabled} /></Field>
            <Field label="City"><Input {...register('city')} disabled={disabled} /></Field>
            <Field label="Current Address"><Input {...register('currentAddress')} disabled={disabled} /></Field>
            <Field label="Permanent Address"><Input {...register('permanentAddress')} disabled={disabled} /></Field>
            {/* Relocated from Form 2 (§3.2) — same fields, same validation; PAN/account stay confidential. */}
            <Field label="Alternate Number"><Input {...register('alternateNumber')} disabled={disabled} /></Field>
            <Field label="Vehicle No (2W/4W)"><Input {...register('vehicleNo2W4W')} disabled={disabled} /></Field>
            <Field label="PAN Number (confidential)"><Input {...register('panNumber')} disabled={disabled} /></Field>
            <Field label="Axis Account Number (confidential)"><Input {...register('axisAccountNumber')} disabled={disabled} /></Field>
            {/* Display rename only — the key stays closestRelativeName (§3.2). */}
            <Field label="Emergency Contact"><Input {...register('closestRelativeName')} disabled={disabled} /></Field>
          </div>

          <ArraySection
            title="Educational Qualifications"
            rows={edu.fields}
            onAdd={() => edu.append({ qualification: '', university: '', yearOfPassing: '', percentage: '' })}
            onRemove={edu.remove}
            disabled={disabled}
            cols={['Qualification', 'University', 'Year', '%']}
            fieldNames={(i) => [
              `educationalQualifications.${i}.qualification`,
              `educationalQualifications.${i}.university`,
              `educationalQualifications.${i}.yearOfPassing`,
              `educationalQualifications.${i}.percentage`,
            ]}
            register={register}
          />

          <ArraySection
            title="Family Details"
            rows={fam.fields}
            onAdd={() => fam.append({ name: '', age: '', relation: '', occupation: '' })}
            onRemove={fam.remove}
            disabled={disabled}
            cols={['Name', 'Age', 'Relation', 'Occupation']}
            fieldNames={(i) => [
              `familyDetails.${i}.name`,
              `familyDetails.${i}.age`,
              `familyDetails.${i}.relation`,
              `familyDetails.${i}.occupation`,
            ]}
            register={register}
          />

          <ArraySection
            title="Conduct References (at least two)"
            rows={refs.fields}
            onAdd={() => refs.append({ name: '', address: '', phone: '' })}
            onRemove={refs.remove}
            disabled={disabled}
            cols={['Name', 'Address', 'Phone']}
            fieldNames={(i) => [
              `characterReferences.${i}.name`,
              `characterReferences.${i}.address`,
              `characterReferences.${i}.phone`,
            ]}
            register={register}
          />

          <div className="space-y-2">
            <span className="text-sm font-semibold">Declaration</span>
            <p className={cn(surface(), 'p-3 text-sm leading-relaxed text-muted-foreground')}>
              {DECLARATION_TEXT}
            </p>
            <label className="flex items-start gap-2 text-sm">
              <input
                type="checkbox"
                className="mt-0.5 size-4 shrink-0 rounded border-input accent-primary"
                checked={declarationAccepted}
                disabled={disabled}
                onChange={(e) =>
                  setValue('declaration', e.target.checked ? DECLARATION_TEXT : '', {
                    shouldDirty: true,
                    shouldValidate: true,
                  })
                }
              />
              <span>{declarationLabel}</span>
            </label>
          </div>

          <StepActions saving={save.isPending} nextLabel={submitLabel} />
        </CardContent>
      </Card>
    </form>
  );
}

function ArraySection({
  title,
  rows,
  onAdd,
  onRemove,
  disabled,
  cols,
  fieldNames,
  register,
}: {
  title: string;
  rows: { id: string }[];
  onAdd: () => void;
  onRemove: (i: number) => void;
  disabled: boolean;
  cols: string[];
  fieldNames: (i: number) => string[];
  register: UseFormRegister<Form1Values>;
}) {
  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between">
        <h4 className="text-sm font-semibold">{title}</h4>
        {!disabled ? (
          <Button type="button" size="sm" variant="outline" onClick={onAdd}>
            <Plus className="size-4" />
            Add
          </Button>
        ) : null}
      </div>
      {rows.length === 0 ? (
        <p className="text-xs text-muted-foreground">None added.</p>
      ) : (
        <div className="space-y-2">
          {rows.map((row, i) => {
            const names = fieldNames(i);
            return (
              <div key={row.id} className={cn(surface(), 'flex flex-wrap items-start gap-2 p-2')}>
                {cols.map((col, ci) => (
                  <div key={col} className="min-w-[140px] flex-1 space-y-1">
                    <label className="text-[11px] text-muted-foreground">{col}</label>
                    {/* eslint-disable-next-line @typescript-eslint/no-explicit-any */}
                    <Input {...register(names[ci] as any)} disabled={disabled} />
                  </div>
                ))}
                {!disabled ? (
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon"
                    className="mt-5"
                    onClick={() => onRemove(i)}
                    aria-label="Remove row"
                  >
                    <Trash2 className="size-4 text-destructive" />
                  </Button>
                ) : null}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Form 3 — Previous Employment (repeatable) — internal identity; shown to the employee as "Form 2".
// ---------------------------------------------------------------------------

export function Form3Step({
  form3,
  disabled,
  onSaved,
  onNext,
  onBack,
  submitLabel,
  title = 'Form 2 — Previous Employment',
  savedMessage = 'Form 2 saved',
  emptyText = 'No previous employment. Add an employer, or continue if this is your first job.',
}: {
  form3: OnboardingDashboard['form3'];
  disabled: boolean;
  onSaved: () => void;
  onNext?: () => void;
  onBack?: () => void;
  submitLabel?: string;
  /** Labels default to the employee's numbering; HR's entry page passes its own (§3.2). */
  title?: string;
  savedMessage?: string;
  emptyText?: string;
}) {
  const queryClient = useQueryClient();
  const { register, control, handleSubmit } = useForm<Form3Values>({
    resolver: zodResolver(Form3Schema),
    defaultValues: {
      entries: form3.map((e) => ({
        companyName: s(e.companyName),
        companyAddress: s(e.companyAddress),
        dateOfJoining: s(e.dateOfJoining),
        dateOfRelieving: s(e.dateOfRelieving),
        designation: s(e.designation),
        lastDrawnSalary: s(e.lastDrawnSalary),
        jobType: s(e.jobType),
        reasonForLeaving: s(e.reasonForLeaving),
        reportingTo: s(e.reportingTo),
        roContact: s(e.roContact),
        hrNameContact: s(e.hrNameContact),
      })),
    },
  });
  const entries = useFieldArray({ control, name: 'entries' });
  const target = useOnboardingTarget();
  const save = useApiMutation((v: Form3Values) => target.saveForm3(v), {
    successMessage: savedMessage,
    onSuccess: (saved) => {
      queryClient.setQueryData<OnboardingDashboard>(target.queryKey, (d) => (d ? { ...d, form3: saved } : d));
      onSaved();
      onNext?.();
    },
  });

  const blank = {
    companyName: '', companyAddress: '', dateOfJoining: '', dateOfRelieving: '', designation: '',
    lastDrawnSalary: '', jobType: '', reasonForLeaving: '', reportingTo: '', roContact: '', hrNameContact: '',
  };

  return (
    <form onSubmit={handleSubmit((v) => save.mutate(v))} noValidate>
      <Card>
        <CardHeader className="flex-row items-center justify-between space-y-0">
          <CardTitle className="text-base">{title}</CardTitle>
          {!disabled ? (
            <Button type="button" size="sm" variant="outline" onClick={() => entries.append(blank)}>
              <Plus className="size-4" />
              Add employer
            </Button>
          ) : null}
        </CardHeader>
        <CardContent className="space-y-4">
          {entries.fields.length === 0 ? (
            <p className="text-sm text-muted-foreground">{emptyText}</p>
          ) : (
            entries.fields.map((row, i) => (
              <div key={row.id} className={cn(surface(), 'space-y-3 p-3')}>
                <div className="flex items-center justify-between">
                  <span className="text-sm font-semibold">Employer {i + 1}</span>
                  {!disabled ? (
                    <Button type="button" variant="ghost" size="icon" onClick={() => entries.remove(i)} aria-label="Remove employer">
                      <Trash2 className="size-4 text-destructive" />
                    </Button>
                  ) : null}
                </div>
                <div className="grid gap-3 sm:grid-cols-2">
                  <Field label="Company (previous employer)"><Input {...register(`entries.${i}.companyName`)} disabled={disabled} /></Field>
                  <Field label="Company Address"><Input {...register(`entries.${i}.companyAddress`)} disabled={disabled} /></Field>
                  <Field label="Date of Joining"><Input type="date" {...register(`entries.${i}.dateOfJoining`)} disabled={disabled} /></Field>
                  <Field label="Date of Relieving"><Input type="date" {...register(`entries.${i}.dateOfRelieving`)} disabled={disabled} /></Field>
                  <Field label="Designation"><Input {...register(`entries.${i}.designation`)} disabled={disabled} /></Field>
                  <Field label="Last Drawn Salary (confidential)"><Input {...register(`entries.${i}.lastDrawnSalary`)} disabled={disabled} /></Field>
                  <Field label="Job Type"><Input {...register(`entries.${i}.jobType`)} disabled={disabled} /></Field>
                  <Field label="Reason for Leaving"><Input {...register(`entries.${i}.reasonForLeaving`)} disabled={disabled} /></Field>
                  <Field label="Reporting To"><Input {...register(`entries.${i}.reportingTo`)} disabled={disabled} /></Field>
                  <Field label="RO Contact"><Input {...register(`entries.${i}.roContact`)} disabled={disabled} /></Field>
                  <Field label="HR Name / Contact"><Input {...register(`entries.${i}.hrNameContact`)} disabled={disabled} /></Field>
                </div>
              </div>
            ))
          )}
          <StepActions onBack={onBack} saving={save.isPending} nextLabel={submitLabel} />
        </CardContent>
      </Card>
    </form>
  );
}

// ---------------------------------------------------------------------------
// Form 4 — Documents (uploads) — internal identity; shown to the employee as "Form 3".
// ---------------------------------------------------------------------------

export function Form4Step({
  dashboard,
  disabled,
  onNext,
  onBack,
  title = 'Form 3 — Documents',
}: {
  dashboard: OnboardingDashboard;
  disabled: boolean;
  onNext: () => void;
  onBack: () => void;
  title?: string;
}) {
  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">{title}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-6">
        <DocGroup title="Educational">
          {EDUCATION_SLOTS.map((t) => (
            <DocumentUploader key={t} docType={t} documents={dashboard.documents} disabled={disabled} />
          ))}
        </DocGroup>

        {EMPLOYMENT_GROUPS.map((g) => (
          <DocGroup key={g} title={`Employment ${g}`}>
            {EMPLOYMENT_SLOTS.map((t) => (
              <DocumentUploader
                key={`${t}-${g}`}
                docType={t}
                groupIndex={g}
                label={`${DOCUMENT_TYPE_LABELS[t]} (Emp ${g})`}
                documents={dashboard.documents}
                disabled={disabled}
              />
            ))}
          </DocGroup>
        ))}

        <DocGroup title="Identity Proofs">
          {IDENTITY_SLOTS.map((t) => (
            <DocumentUploader
              key={t}
              docType={t}
              required={t === 'AADHAAR' || t === 'PAN' || (t === 'ITR' && dashboard.itrRequired)}
              documents={dashboard.documents}
              disabled={disabled}
            />
          ))}
        </DocGroup>

        <StepActions onBack={onBack} onNext={onNext} nextLabel="Continue" />
      </CardContent>
    </Card>
  );
}

function DocGroup({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="space-y-3">
      <h4 className="text-sm font-semibold text-muted-foreground">{title}</h4>
      <div className="grid gap-5 sm:grid-cols-2 lg:grid-cols-3">{children}</div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Review + Sign & Submit
// ---------------------------------------------------------------------------

function ReviewStep({
  dashboard,
  onNext,
  onBack,
}: {
  dashboard: OnboardingDashboard;
  onNext: () => void;
  onBack: () => void;
}) {
  const { missing } = evaluateOnboarding(
    dashboard.form1,
    dashboard.documents,
    dashboard.signature,
    dashboard.itrRequired,
  );
  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">Review</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4 text-sm">
        <SummaryRow label="Form 1 — Personal" value={dashboard.form1?.name ?? 'Not completed'} ok={!!dashboard.form1?.name} />
        <SummaryRow label="Form 2 — Previous Employment" value={`${dashboard.form3.length} employer(s)`} ok />
        <SummaryRow label="Form 3 — Documents" value={`${dashboard.documents.length} uploaded`} ok={dashboard.documents.length > 0} />
        <SummaryRow label="Signature" value={dashboard.signature ? 'Captured' : 'Not signed'} ok={!!dashboard.signature} />
        {missing.length > 0 ? (
          <div className="rounded-md border border-warning/40 bg-warning/5 p-3">
            <p className="font-medium">Before you can submit:</p>
            <ul className="ml-4 list-disc text-muted-foreground">
              {missing.map((m) => (
                <li key={m}>{m}</li>
              ))}
            </ul>
          </div>
        ) : (
          <p className="text-success">Everything looks complete — continue to sign &amp; submit.</p>
        )}
        <StepActions onBack={onBack} onNext={onNext} nextLabel="Continue" />
      </CardContent>
    </Card>
  );
}

function SummaryRow({ label, value, ok }: { label: string; value: string; ok: boolean }) {
  return (
    <div className="flex items-center justify-between border-b py-2">
      <span className="text-muted-foreground">{label}</span>
      <span className={cn('font-medium', ok ? 'text-foreground' : 'text-destructive')}>{value}</span>
    </div>
  );
}

/** The adopted signature PNG (Blob) → a data URL, so it stores via the existing signature endpoint unchanged. */
export function blobToDataUrl(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result as string);
    reader.onerror = () => reject(reader.error);
    reader.readAsDataURL(blob);
  });
}

function SignStep({
  dashboard,
  disabled,
  onSaved,
  onBack,
}: {
  dashboard: OnboardingDashboard;
  disabled: boolean;
  onSaved: () => void;
  onBack: () => void;
}) {
  const queryClient = useQueryClient();
  const { complete, missing } = evaluateOnboarding(
    dashboard.form1,
    dashboard.documents,
    dashboard.signature,
    dashboard.itrRequired,
  );

  // The adopted signature is a PNG blob (whatever the capture mode); it is stored exactly as before — the
  // same PUT /me/onboarding/signature with the same data-URL shape. `type` is fixed so downstream (storage,
  // PDF stamp) can't tell which mode produced it.
  const target = useOnboardingTarget();
  const saveSig = useApiMutation(
    async (blob: Blob) =>
      target.saveSignature({ imageDataUrl: await blobToDataUrl(blob), type: 'DRAWN' }),
    { successMessage: 'Signature saved', onSuccess: onSaved },
  );

  const submit = useApiMutation(() => submitOnboarding(), {
    successMessage: 'Submitted for verification',
    // The generated PDFs are produced just after the submit commits, so refetch to display them.
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['onboarding'] }),
  });

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">Sign &amp; Submit</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        {dashboard.signature ? (
          <p className="text-sm text-success">Signed. Adopt a new one below to replace it.</p>
        ) : (
          <p className="text-sm text-muted-foreground">
            Sign, generate, or upload one signature — it is stamped onto your generated forms.
          </p>
        )}
        <LazySignatureCapture
          fullName={dashboard.form1?.name ?? ''}
          onAdopt={(blob) => saveSig.mutate(blob)}
          disabled={disabled || saveSig.isPending}
        />

        {missing.length > 0 ? (
          <div className="rounded-md border border-warning/40 bg-warning/5 p-3 text-sm">
            <p className="font-medium">Still needed:</p>
            <ul className="ml-4 list-disc text-muted-foreground">
              {missing.map((m) => (
                <li key={m}>{m}</li>
              ))}
            </ul>
          </div>
        ) : null}

        <div className="flex justify-between pt-2">
          <Button type="button" variant="ghost" onClick={onBack}>
            <ArrowLeft className="size-4" />
            Back
          </Button>
          <Button
            type="button"
            onClick={() => submit.mutate()}
            disabled={disabled || !complete || submit.isPending}
          >
            <Send className="size-4" />
            {submit.isPending ? 'Submitting…' : 'Submit for verification'}
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}
