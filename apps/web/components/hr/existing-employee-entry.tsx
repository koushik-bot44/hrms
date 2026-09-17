'use client';

import * as React from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { ArrowLeft, BadgeCheck, CheckCircle2, UserRound } from 'lucide-react';
import { evaluateOnboarding, type EmployeeRecord, type OnboardingDashboard } from '@/lib/contract';
import {
  approveExistingEmployee,
  employeeOnboardingTarget,
  getEmployeeOnboarding,
} from '@/lib/api/onboarding';
import { useApiMutation, useApiQuery } from '@/lib/api/hooks';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { EmptyState } from '@/components/empty-state';
import { LoadingSkeleton } from '@/components/loading-skeleton';
import { StatusBadge } from '@/components/status-badge';
import { cn } from '@/lib/utils';
import {
  Form1Step,
  Form3Step,
  Form4Step,
  Stepper,
  blobToDataUrl,
} from '@/components/employee/onboarding-stepper';
import { OnboardingTargetProvider, useOnboardingTarget } from '@/components/employee/onboarding-target';
import { LazySignatureCapture } from '@/components/signature/lazy-signature-capture';
import { EditEmployeeInfoDialog } from '@/components/employee-info/edit-employee-info-dialog';

const STEPS = ['Personal', 'Prev. Employment', 'Documents', 'Signature', 'Review & approve'];

/** The shared completeness messages are written to the employee ("upload your …", their form numbers) — reword for HR. */
function forHr(message: string): string {
  return message
    .replace(/^Form 3 — upload your/, 'Form 4 — upload their')
    .replace('Form 1 — confirm the declaration', 'Form 1 — confirm they signed the declaration')
    .replace('Sign to submit', 'Upload their signature');
}

/**
 * HR enters an EXISTING employee's record (§3.2): someone who already works here never signs in to onboard and
 * gets no email. HR fills Personal → Previous Employment → Documents, uploads a scan of their signature, then
 * approves — no separate verify step, since HR entered the data. Approval keeps the employee ID HR entered.
 * Rendered in place of the verification workspace until the record is approved.
 */
export function ExistingEmployeeEntry({
  record,
  onChanged,
}: {
  record: EmployeeRecord;
  /** Refresh the record (and lists) after a Form 2 edit or the approval. */
  onChanged: () => void;
}) {
  const target = React.useMemo(() => employeeOnboardingTarget(record.id), [record.id]);
  const queryClient = useQueryClient();
  const [step, setStep] = React.useState(0);
  const query = useApiQuery(target.queryKey, (signal) => getEmployeeOnboarding(record.id, signal));
  // Refresh the entry data and the record (its status moves to IN_PROGRESS on the first save).
  const refetch = () => {
    void queryClient.invalidateQueries({ queryKey: target.queryKey });
    onChanged();
  };
  const goNext = () => setStep((n) => Math.min(n + 1, STEPS.length - 1));
  const goBack = () => setStep((n) => Math.max(n - 1, 0));
  const dashboard = query.data;

  return (
    <OnboardingTargetProvider target={target}>
      <div className="space-y-5">
        <Card>
          <CardHeader className="flex-col gap-3 sm:flex-row sm:items-start sm:justify-between sm:space-y-0">
            <div className="min-w-0 space-y-1">
              <CardTitle className="text-base">{record.fullName ?? record.email}</CardTitle>
              <p className="truncate text-sm text-muted-foreground">{record.email}</p>
              <p className="text-xs text-muted-foreground">
                {record.designation ?? '—'}
                {record.dateOfJoining ? ` · joined ${record.dateOfJoining}` : ''}
                {record.form2?.employeeId ? (
                  <>
                    {' · '}
                    <span className="font-mono">{record.form2.employeeId}</span>
                  </>
                ) : null}
              </p>
            </div>
            <div className="flex flex-wrap items-center gap-2">
              <Badge variant="neutral">Existing employee</Badge>
              <StatusBadge status={record.status} />
              <EditEmployeeInfoDialog
                employeeId={record.id}
                form2={record.form2}
                employeeCode={record.employeeCode}
                onboardingType={record.onboardingType}
                onSaved={onChanged}
              />
            </div>
          </CardHeader>
          <CardContent>
            <p className="text-sm text-muted-foreground">
              They don&apos;t sign in to onboard and get no invite or welcome email. Enter their details and
              documents, upload a scan of their signature, then approve — their employee ID and official email
              stay as entered.
            </p>
          </CardContent>
        </Card>

        {query.isLoading ? (
          <div className="space-y-4">
            <LoadingSkeleton lines={1} />
            <LoadingSkeleton lines={6} />
          </div>
        ) : !dashboard ? (
          <EmptyState
            icon={UserRound}
            title="Couldn't load their record"
            description={query.error?.message ?? 'Please try again.'}
          />
        ) : (
          <>
            {query.isError ? (
              <div className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-warning/40 bg-warning/5 p-3 text-sm">
                <span>Couldn&apos;t refresh their record — your entries are still here.</span>
                <Button type="button" size="sm" variant="outline" onClick={() => void query.refetch()}>
                  Retry
                </Button>
              </div>
            ) : null}
            <Stepper
              steps={STEPS}
              step={step}
              onStep={setStep}
              done={[
                Boolean(dashboard.form1?.name),
                dashboard.form3.length > 0,
                dashboard.documents.length > 0,
                Boolean(dashboard.signature),
                false,
              ]}
            />
            {step === 0 && (
              <Form1Step
                form1={dashboard.form1}
                disabled={false}
                onSaved={refetch}
                onNext={goNext}
                declarationLabel="The employee has signed this declaration (copy on file)."
              />
            )}
            {step === 1 && (
              <Form3Step
                form3={dashboard.form3}
                disabled={false}
                onSaved={refetch}
                onNext={goNext}
                onBack={goBack}
                title="Form 3 — Previous Employment"
                savedMessage="Form 3 saved"
                emptyText="No previous employment. Add an employer, or continue if they have none."
              />
            )}
            {step === 2 && (
              <Form4Step
                dashboard={dashboard}
                disabled={false}
                onNext={goNext}
                onBack={goBack}
                title="Form 4 — Documents"
              />
            )}
            {step === 3 && (
              <SignatureStep
                dashboard={dashboard}
                fullName={record.fullName ?? ''}
                onSaved={refetch}
                onNext={goNext}
                onBack={goBack}
              />
            )}
            {step === 4 && (
              <ApproveStep
                employeeId={record.id}
                existingId={record.form2?.employeeId ?? null}
                dashboard={dashboard}
                onBack={goBack}
                onApproved={onChanged}
              />
            )}
          </>
        )}
      </div>
    </OnboardingTargetProvider>
  );
}

/** HR uploads a scan of the employee's own signature (e.g. from their signed joining papers). */
function SignatureStep({
  dashboard,
  fullName,
  onSaved,
  onNext,
  onBack,
}: {
  dashboard: OnboardingDashboard;
  fullName: string;
  onSaved: () => void;
  onNext: () => void;
  onBack: () => void;
}) {
  const target = useOnboardingTarget();
  // Stored exactly like an employee-adopted signature (same PNG data-URL contract), so the PDF stamp is unchanged.
  const save = useApiMutation(
    async (blob: Blob) =>
      target.saveSignature({ imageDataUrl: await blobToDataUrl(blob), type: 'DRAWN' }),
    { successMessage: 'Signature saved', onSuccess: onSaved },
  );

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">Employee signature</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        {dashboard.signature ? (
          <p className="text-sm text-success">Signature on file. Upload a new scan below to replace it.</p>
        ) : (
          <p className="text-sm text-muted-foreground">
            Upload a scan or photo of their signature — it is stamped onto their generated forms.
          </p>
        )}
        <LazySignatureCapture
          fullName={fullName}
          modes={['upload', 'clean']}
          adoptNotice="Use only the employee's own signature, taken from a document they signed."
          onAdopt={(blob) => save.mutate(blob)}
          disabled={save.isPending}
        />
        <div className="flex justify-between pt-2">
          <Button type="button" variant="ghost" onClick={onBack}>
            <ArrowLeft className="size-4" />
            Back
          </Button>
          <Button type="button" variant="outline" onClick={onNext}>
            Continue
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}

/** Completeness check (same rules as the employee's submit) + the approval, which keeps their existing ID. */
function ApproveStep({
  employeeId,
  existingId,
  dashboard,
  onBack,
  onApproved,
}: {
  employeeId: string;
  /** The employee ID HR entered on Form 2 — kept on approval. */
  existingId: string | null;
  dashboard: OnboardingDashboard;
  onBack: () => void;
  onApproved: () => void;
}) {
  const { complete, missing } = evaluateOnboarding(
    dashboard.form1,
    dashboard.documents,
    dashboard.signature,
    dashboard.itrRequired,
  );
  const approve = useApiMutation(() => approveExistingEmployee(employeeId), {
    successMessage: (r) => `Approved — employee ID ${r.employeeCode ?? existingId ?? ''}`.trim(),
    onSuccess: onApproved,
  });

  const rows: { label: string; value: string; ok: boolean }[] = [
    { label: 'Employee ID', value: existingId ?? 'Not entered', ok: Boolean(existingId) },
    { label: 'Personal details', value: dashboard.form1?.name ?? 'Not entered', ok: Boolean(dashboard.form1?.name) },
    { label: 'Previous employment', value: `${dashboard.form3.length} employer(s)`, ok: true },
    {
      label: 'Documents',
      value: `${dashboard.documents.length} uploaded`,
      ok: dashboard.documents.length > 0,
    },
    { label: 'Signature', value: dashboard.signature ? 'On file' : 'Not uploaded', ok: Boolean(dashboard.signature) },
  ];

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">Review &amp; approve</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4 text-sm">
        <div>
          {rows.map((r) => (
            <div key={r.label} className="flex items-center justify-between border-b py-2">
              <span className="text-muted-foreground">{r.label}</span>
              <span className={cn('font-medium', r.ok ? 'text-foreground' : 'text-destructive')}>{r.value}</span>
            </div>
          ))}
        </div>
        {missing.length > 0 || !existingId ? (
          <div className="rounded-md border border-warning/40 bg-warning/5 p-3">
            <p className="font-medium">Before you can approve:</p>
            <ul className="ml-4 list-disc text-muted-foreground">
              {!existingId ? <li>Enter their employee ID (Edit Employee Info)</li> : null}
              {missing.map((m) => (
                <li key={m}>{forHr(m)}</li>
              ))}
            </ul>
          </div>
        ) : (
          <p className="flex items-center gap-2 text-success">
            <CheckCircle2 className="size-4" />
            The record is complete. Approving keeps their employee ID{existingId ? ` ${existingId}` : ''} — no
            email is sent.
          </p>
        )}
        <div className="flex justify-between pt-2">
          <Button type="button" variant="ghost" onClick={onBack}>
            <ArrowLeft className="size-4" />
            Back
          </Button>
          <Button
            type="button"
            onClick={() => approve.mutate()}
            disabled={!complete || !existingId || approve.isPending || approve.isSuccess}
          >
            <BadgeCheck className="size-4" />
            {approve.isPending ? 'Approving…' : 'Approve'}
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}
