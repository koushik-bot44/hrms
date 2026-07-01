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
  Form1Schema,
  Form2Schema,
  Form3Schema,
  IDENTITY_SLOTS,
  DOCUMENT_TYPE_LABELS,
  evaluateOnboarding,
  type Form1Values,
  type Form1View,
  type Form2Values,
  type Form2View,
  type Form3Values,
  type OnboardingDashboard,
} from '@/lib/contract';
import { saveForm1, saveForm2, saveForm3, saveSignature, submitOnboarding } from '@/lib/api/onboarding';
import { useApiMutation } from '@/lib/api/hooks';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { cn } from '@/lib/utils';
import { DocumentUploader } from '@/components/employee/document-uploader';
import { SignaturePad } from '@/components/employee/signature-pad';

const STEPS = ['Personal', 'Employee Info', 'Prev. Employment', 'Documents', 'Review', 'Sign & Submit'];
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
      <Stepper step={step} onStep={setStep} dashboard={dashboard} />
      {step === 0 && (
        <Form1Step form1={dashboard.form1} disabled={disabled} onSaved={refetch} onNext={goNext} />
      )}
      {step === 1 && (
        <Form2Step
          form2={dashboard.form2}
          employeeCode={dashboard.employeeCode}
          disabled={disabled}
          onSaved={refetch}
          onNext={goNext}
          onBack={goBack}
        />
      )}
      {step === 2 && (
        <Form3Step form3={dashboard.form3} disabled={disabled} onSaved={refetch} onNext={goNext} onBack={goBack} />
      )}
      {step === 3 && <Form4Step dashboard={dashboard} disabled={disabled} onNext={goNext} onBack={goBack} />}
      {step === 4 && <ReviewStep dashboard={dashboard} onNext={goNext} onBack={goBack} />}
      {step === 5 && (
        <SignStep dashboard={dashboard} disabled={disabled} onSaved={refetch} onBack={goBack} />
      )}
    </div>
  );
}

function Stepper({
  step,
  onStep,
  dashboard,
}: {
  step: number;
  onStep: (n: number) => void;
  dashboard: OnboardingDashboard;
}) {
  const done = [
    Boolean(dashboard.form1?.name),
    Boolean(dashboard.form2?.fullName),
    dashboard.form3.length > 0,
    dashboard.documents.length > 0,
    false,
    Boolean(dashboard.signature),
  ];
  return (
    <div className="flex flex-wrap gap-2">
      {STEPS.map((label, i) => (
        <button
          key={label}
          type="button"
          onClick={() => onStep(i)}
          className={cn(
            'flex items-center gap-2 rounded-full border px-3 py-1.5 text-xs font-medium transition-colors',
            i === step ? 'border-primary bg-primary text-primary-foreground' : 'border-border hover:border-primary/50',
          )}
        >
          <span
            className={cn(
              'flex size-5 items-center justify-center rounded-full text-[11px]',
              i === step ? 'bg-primary-foreground/20' : 'bg-muted',
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
    designation: s(f?.designation),
    offeredCtc: s(f?.offeredCtc),
    currentAddress: s(f?.currentAddress),
    permanentAddress: s(f?.permanentAddress),
    maritalStatus: s(f?.maritalStatus),
    bloodGroup: s(f?.bloodGroup),
    closestRelativeName: s(f?.closestRelativeName),
    closestRelativePhone: s(f?.closestRelativePhone),
    city: s(f?.city),
    relationship: s(f?.relationship),
    declaration: s(f?.declaration),
    educationalQualifications: (f?.educationalQualifications ?? []).map((e) => ({
      qualification: s(e.qualification),
      university: s(e.university),
      yearOfPassing: s(e.yearOfPassing),
      percentage: s(e.percentage),
    })),
    workingExperiences: (f?.workingExperiences ?? []).map((w) => ({
      organization: s(w.organization),
      period: s(w.period),
      designation: s(w.designation),
      salaryCtc: s(w.salaryCtc),
      reasonForLeaving: s(w.reasonForLeaving),
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

function Form1Step({
  form1,
  disabled,
  onSaved,
  onNext,
}: {
  form1: Form1View | null;
  disabled: boolean;
  onSaved: () => void;
  onNext: () => void;
}) {
  const { register, control, handleSubmit, formState } = useForm<Form1Values>({
    resolver: zodResolver(Form1Schema),
    defaultValues: form1Defaults(form1),
  });
  const errors = formState.errors;
  const edu = useFieldArray({ control, name: 'educationalQualifications' });
  const work = useFieldArray({ control, name: 'workingExperiences' });
  const fam = useFieldArray({ control, name: 'familyDetails' });
  const refs = useFieldArray({ control, name: 'characterReferences' });

  const save = useApiMutation((v: Form1Values) => saveForm1(v), {
    successMessage: 'Form 1 saved',
    onSuccess: () => {
      onSaved();
      onNext();
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
            <Field label="Date of birth" error={errors.dateOfBirth?.message}>
              <Input type="date" {...register('dateOfBirth')} disabled={disabled} />
            </Field>
            <Field label="Email"><Input type="email" {...register('email')} disabled={disabled} /></Field>
            <Field label="Mobile"><Input {...register('mobile')} disabled={disabled} /></Field>
            <Field label="Designation"><Input {...register('designation')} disabled={disabled} /></Field>
            <Field label="Offered CTC (confidential)">
              <Input {...register('offeredCtc')} disabled={disabled} placeholder="e.g. 1200000" />
            </Field>
            <Field label="Marital status"><Input {...register('maritalStatus')} disabled={disabled} /></Field>
            <Field label="Blood group"><Input {...register('bloodGroup')} disabled={disabled} /></Field>
            <Field label="City"><Input {...register('city')} disabled={disabled} /></Field>
            <Field label="Current address"><Input {...register('currentAddress')} disabled={disabled} /></Field>
            <Field label="Permanent address"><Input {...register('permanentAddress')} disabled={disabled} /></Field>
            <Field label="Closest relative"><Input {...register('closestRelativeName')} disabled={disabled} /></Field>
            <Field label="Relative phone"><Input {...register('closestRelativePhone')} disabled={disabled} /></Field>
            <Field label="Relationship"><Input {...register('relationship')} disabled={disabled} /></Field>
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
            title="Working Experience"
            rows={work.fields}
            onAdd={() => work.append({ organization: '', period: '', designation: '', salaryCtc: '', reasonForLeaving: '' })}
            onRemove={work.remove}
            disabled={disabled}
            cols={['Organization', 'Period', 'Designation', 'Salary/CTC (confidential)', 'Reason for leaving']}
            fieldNames={(i) => [
              `workingExperiences.${i}.organization`,
              `workingExperiences.${i}.period`,
              `workingExperiences.${i}.designation`,
              `workingExperiences.${i}.salaryCtc`,
              `workingExperiences.${i}.reasonForLeaving`,
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
            title="Character References (at least two)"
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

          <Field label="Declaration">
            <textarea
              rows={3}
              {...register('declaration')}
              disabled={disabled}
              className="flex w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            />
          </Field>

          <StepActions saving={save.isPending} />
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
              <div key={row.id} className="flex flex-wrap items-start gap-2 rounded-md border p-2">
                {cols.map((col, ci) => (
                  <div key={col} className="min-w-[140px] flex-1 space-y-1">
                    <label className="text-[11px] text-muted-foreground">{col}</label>
                    {/* eslint-disable-next-line @typescript-eslint/no-explicit-any */}
                    <Input {...register(names[ci] as any)} disabled={disabled} className="h-9" />
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
// Form 2 — Employee Info
// ---------------------------------------------------------------------------

function form2Defaults(f: Form2View | null): Form2Values {
  return {
    fullName: s(f?.fullName),
    fatherName: s(f?.fatherName),
    dateOfBirth: s(f?.dateOfBirth),
    dateOfJoining: s(f?.dateOfJoining),
    bloodGroup: s(f?.bloodGroup),
    mobile: s(f?.mobile),
    alternateNumber: s(f?.alternateNumber),
    officialEmail: s(f?.officialEmail),
    personalEmail: s(f?.personalEmail),
    designation: s(f?.designation),
    documentSubmitted: s(f?.documentSubmitted),
    vehicleNo2W4W: s(f?.vehicleNo2W4W),
    panNumber: s(f?.panNumber),
    axisAccountNumber: s(f?.axisAccountNumber),
    currentAddress: s(f?.currentAddress),
    permanentAddress: s(f?.permanentAddress),
  };
}

function Form2Step({
  form2,
  employeeCode,
  disabled,
  onSaved,
  onNext,
  onBack,
}: {
  form2: Form2View | null;
  employeeCode: string | null;
  disabled: boolean;
  onSaved: () => void;
  onNext: () => void;
  onBack: () => void;
}) {
  const { register, handleSubmit } = useForm<Form2Values>({
    resolver: zodResolver(Form2Schema),
    defaultValues: form2Defaults(form2),
  });
  const save = useApiMutation((v: Form2Values) => saveForm2(v), {
    successMessage: 'Form 2 saved',
    onSuccess: () => {
      onSaved();
      onNext();
    },
  });

  return (
    <form onSubmit={handleSubmit((v) => save.mutate(v))} noValidate>
      <Card>
        <CardHeader>
          <CardTitle className="text-base">Form 2 — Employee Info</CardTitle>
        </CardHeader>
        <CardContent className="space-y-5">
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Full name"><Input {...register('fullName')} disabled={disabled} /></Field>
            <Field label="Father's name"><Input {...register('fatherName')} disabled={disabled} /></Field>
            <Field label="Employee ID (assigned on approval)">
              <Input value={employeeCode ?? ''} readOnly disabled placeholder="—" />
            </Field>
            <Field label="Spark ID (set by HR)">
              <Input value={form2?.sparkId ?? ''} readOnly disabled placeholder="—" />
            </Field>
            <Field label="Date of birth"><Input type="date" {...register('dateOfBirth')} disabled={disabled} /></Field>
            <Field label="Date of joining"><Input type="date" {...register('dateOfJoining')} disabled={disabled} /></Field>
            <Field label="Blood group"><Input {...register('bloodGroup')} disabled={disabled} /></Field>
            <Field label="Mobile"><Input {...register('mobile')} disabled={disabled} /></Field>
            <Field label="Alternate number"><Input {...register('alternateNumber')} disabled={disabled} /></Field>
            <Field label="Official email"><Input type="email" {...register('officialEmail')} disabled={disabled} /></Field>
            <Field label="Personal email"><Input type="email" {...register('personalEmail')} disabled={disabled} /></Field>
            <Field label="Designation"><Input {...register('designation')} disabled={disabled} /></Field>
            <Field label="Documents submitted"><Input {...register('documentSubmitted')} disabled={disabled} /></Field>
            <Field label="Vehicle no (2W/4W)"><Input {...register('vehicleNo2W4W')} disabled={disabled} /></Field>
            <Field label="PAN number (confidential)"><Input {...register('panNumber')} disabled={disabled} /></Field>
            <Field label="Axis account number (confidential)"><Input {...register('axisAccountNumber')} disabled={disabled} /></Field>
            <Field label="Current address"><Input {...register('currentAddress')} disabled={disabled} /></Field>
            <Field label="Permanent address"><Input {...register('permanentAddress')} disabled={disabled} /></Field>
          </div>
          <StepActions onBack={onBack} saving={save.isPending} />
        </CardContent>
      </Card>
    </form>
  );
}

// ---------------------------------------------------------------------------
// Form 3 — Previous Employment (repeatable)
// ---------------------------------------------------------------------------

function Form3Step({
  form3,
  disabled,
  onSaved,
  onNext,
  onBack,
}: {
  form3: OnboardingDashboard['form3'];
  disabled: boolean;
  onSaved: () => void;
  onNext: () => void;
  onBack: () => void;
}) {
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
  const save = useApiMutation((v: Form3Values) => saveForm3(v), {
    successMessage: 'Form 3 saved',
    onSuccess: () => {
      onSaved();
      onNext();
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
          <CardTitle className="text-base">Form 3 — Previous Employment</CardTitle>
          {!disabled ? (
            <Button type="button" size="sm" variant="outline" onClick={() => entries.append(blank)}>
              <Plus className="size-4" />
              Add employer
            </Button>
          ) : null}
        </CardHeader>
        <CardContent className="space-y-4">
          {entries.fields.length === 0 ? (
            <p className="text-sm text-muted-foreground">
              No previous employment. Add an employer, or continue if this is your first job.
            </p>
          ) : (
            entries.fields.map((row, i) => (
              <div key={row.id} className="space-y-3 rounded-md border p-3">
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
                  <Field label="Company address"><Input {...register(`entries.${i}.companyAddress`)} disabled={disabled} /></Field>
                  <Field label="Date of joining"><Input type="date" {...register(`entries.${i}.dateOfJoining`)} disabled={disabled} /></Field>
                  <Field label="Date of relieving"><Input type="date" {...register(`entries.${i}.dateOfRelieving`)} disabled={disabled} /></Field>
                  <Field label="Designation"><Input {...register(`entries.${i}.designation`)} disabled={disabled} /></Field>
                  <Field label="Last drawn salary (confidential)"><Input {...register(`entries.${i}.lastDrawnSalary`)} disabled={disabled} /></Field>
                  <Field label="Job type"><Input {...register(`entries.${i}.jobType`)} disabled={disabled} /></Field>
                  <Field label="Reason for leaving"><Input {...register(`entries.${i}.reasonForLeaving`)} disabled={disabled} /></Field>
                  <Field label="Reporting to"><Input {...register(`entries.${i}.reportingTo`)} disabled={disabled} /></Field>
                  <Field label="RO contact"><Input {...register(`entries.${i}.roContact`)} disabled={disabled} /></Field>
                  <Field label="HR name / contact"><Input {...register(`entries.${i}.hrNameContact`)} disabled={disabled} /></Field>
                </div>
              </div>
            ))
          )}
          <StepActions onBack={onBack} saving={save.isPending} />
        </CardContent>
      </Card>
    </form>
  );
}

// ---------------------------------------------------------------------------
// Form 4 — Documents (uploads)
// ---------------------------------------------------------------------------

function Form4Step({
  dashboard,
  disabled,
  onNext,
  onBack,
}: {
  dashboard: OnboardingDashboard;
  disabled: boolean;
  onNext: () => void;
  onBack: () => void;
}) {
  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">Form 4 — Documents</CardTitle>
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
              required={t === 'AADHAAR' || t === 'PAN'}
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
    dashboard.form2,
    dashboard.documents,
    dashboard.signature,
  );
  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">Review</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4 text-sm">
        <SummaryRow label="Form 1 — Personal" value={dashboard.form1?.name ?? 'Not completed'} ok={!!dashboard.form1?.name} />
        <SummaryRow label="Form 2 — Employee Info" value={dashboard.form2?.fullName ?? 'Not completed'} ok={!!dashboard.form2?.fullName} />
        <SummaryRow label="Form 3 — Previous Employment" value={`${dashboard.form3.length} employer(s)`} ok />
        <SummaryRow label="Form 4 — Documents" value={`${dashboard.documents.length} uploaded`} ok={dashboard.documents.length > 0} />
        <SummaryRow label="Signature" value={dashboard.signature ? 'Captured' : 'Not signed'} ok={!!dashboard.signature} />
        {missing.length > 0 ? (
          <div className="rounded-md border border-amber-500/40 bg-amber-500/5 p-3">
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
  const [pending, setPending] = React.useState<{ dataUrl: string; type: 'DRAWN' | 'TYPED' } | null>(null);
  const { complete, missing } = evaluateOnboarding(
    dashboard.form1,
    dashboard.form2,
    dashboard.documents,
    dashboard.signature,
  );

  const saveSig = useApiMutation(
    () => saveSignature({ imageDataUrl: pending!.dataUrl, type: pending!.type }),
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
          <p className="text-sm text-success">
            Signed ({dashboard.signature.type.toLowerCase()}). Re-sign below to replace it.
          </p>
        ) : (
          <p className="text-sm text-muted-foreground">
            Draw or type one signature — it is stamped onto your generated forms.
          </p>
        )}
        <SignaturePad onChange={(dataUrl, type) => setPending(dataUrl ? { dataUrl, type } : null)} disabled={disabled} />
        <div className="flex gap-2">
          <Button
            type="button"
            variant="outline"
            onClick={() => saveSig.mutate()}
            disabled={disabled || !pending || saveSig.isPending}
          >
            {saveSig.isPending ? 'Saving…' : 'Save signature'}
          </Button>
        </div>

        {missing.length > 0 ? (
          <div className="rounded-md border border-amber-500/40 bg-amber-500/5 p-3 text-sm">
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
