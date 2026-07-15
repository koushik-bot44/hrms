'use client';

import * as React from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useQueryClient } from '@tanstack/react-query';
import { CheckCircle2, UserPlus } from 'lucide-react';
import { SuperAdminOnboardSchema, type SuperAdminOnboardInput } from '@/lib/contract';
import { listCompanies } from '@/lib/api/companies';
import { listTeams, teamsKey } from '@/lib/api/teams';
import { onboardForCompany } from '@/lib/api/employees';
import { useApiMutation, useApiQuery } from '@/lib/api/hooks';
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
import { Input } from '@/components/ui/input';

const SELECT_CLASS =
  'flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50';

/** Super Admin onboards into any company (§2): pick company → team → (that team's HR) → details. */
export function SuperAdminOnboardDialog() {
  const [open, setOpen] = React.useState(false);
  const [result, setResult] = React.useState<{ email: string } | null>(null);
  const queryClient = useQueryClient();

  const {
    register,
    handleSubmit,
    reset,
    watch,
    setValue,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<SuperAdminOnboardInput>({
    resolver: zodResolver(SuperAdminOnboardSchema),
    defaultValues: { companyId: '', teamId: '', fullName: '', email: '', designation: '', dateOfJoining: '' },
  });

  const companyId = watch('companyId');
  const teamId = watch('teamId');

  const companies = useApiQuery(['companies'], (signal) => listCompanies(signal), { enabled: open });
  const teams = useApiQuery(teamsKey(companyId), (signal) => listTeams(companyId, signal), {
    enabled: open && Boolean(companyId),
  });

  const selectedTeam = teams.data?.find((t) => t.id === teamId);
  const derivedHr = selectedTeam?.hr ?? null;
  const teamHasNoHr = Boolean(teamId) && teams.isSuccess && !derivedHr;

  const mutation = useApiMutation(
    (values: SuperAdminOnboardInput) =>
      onboardForCompany(values.companyId, {
        teamId: values.teamId,
        fullName: values.fullName,
        email: values.email,
        designation: values.designation,
        dateOfJoining: values.dateOfJoining,
      }),
    {
      successMessage: (data) => `${data.employee.fullName} onboarded`,
      onSuccess: (data) => {
        setResult({ email: data.employee.email });
        reset();
        void queryClient.invalidateQueries({ queryKey: ['company', companyId] });
        void queryClient.invalidateQueries({ queryKey: ['companies'] });
        void queryClient.invalidateQueries({ queryKey: ['dashboard'] });
      },
      onError: (error) => {
        if (error.status === 400 || error.status === 409) {
          setError('email', { message: error.message });
        }
      },
    },
  );

  const onSubmit = handleSubmit((values) => {
    if (teamHasNoHr) return;
    mutation.mutate(values);
  });

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
        <Button size="sm" variant="outline">
          <UserPlus />
          Onboard employee
        </Button>
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
                <span className="font-medium text-foreground">{result.email}</span>. They sign in with
                their full name + email; a unique employee ID is assigned once a Manager approves them.
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
                Choose the company and team — the employee attaches to that team&apos;s HR.
              </DialogDescription>
            </DialogHeader>
            <form onSubmit={onSubmit} className="space-y-4" noValidate>
              <Field id="sa-onb-company" label="Company" error={errors.companyId?.message}>
                <select
                  id="sa-onb-company"
                  className={SELECT_CLASS}
                  aria-invalid={Boolean(errors.companyId)}
                  {...register('companyId')}
                  onChange={(e) => {
                    setValue('companyId', e.target.value, { shouldValidate: true });
                    setValue('teamId', ''); // reset team when company changes
                  }}
                >
                  <option value="">Select a company…</option>
                  {(companies.data ?? []).map((c) => (
                    <option key={c.id} value={c.id}>
                      {c.name} ({c.code})
                    </option>
                  ))}
                </select>
              </Field>

              <Field id="sa-onb-team" label="Team" error={errors.teamId?.message}>
                <select
                  id="sa-onb-team"
                  className={SELECT_CLASS}
                  disabled={!companyId || teams.isLoading}
                  aria-invalid={Boolean(errors.teamId)}
                  {...register('teamId')}
                >
                  <option value="">
                    {!companyId
                      ? 'Choose a company first'
                      : teams.isLoading
                        ? 'Loading teams…'
                        : (teams.data ?? []).length === 0
                          ? 'No teams in this company'
                          : 'Select a team…'}
                  </option>
                  {(teams.data ?? []).map((t) => (
                    <option key={t.id} value={t.id}>
                      {t.name}
                    </option>
                  ))}
                </select>
              </Field>

              {teamId ? (
                <div className="rounded-md border bg-muted/30 px-3 py-2 text-sm">
                  <span className="text-muted-foreground">Onboarding HR: </span>
                  {derivedHr ? (
                    <span className="font-medium">
                      {derivedHr.name} <span className="text-muted-foreground">· {derivedHr.email}</span>
                    </span>
                  ) : (
                    <span className="text-destructive">
                      This team has no HR assigned — assign one before onboarding.
                    </span>
                  )}
                </div>
              ) : null}

              <Field id="sa-onb-name" label="Full name" error={errors.fullName?.message}>
                <Input
                  id="sa-onb-name"
                  placeholder="Alex Doe"
                  aria-invalid={Boolean(errors.fullName)}
                  {...register('fullName')}
                />
              </Field>
              <Field id="sa-onb-email" label="Email" error={errors.email?.message}>
                <Input
                  id="sa-onb-email"
                  type="email"
                  placeholder="new.hire@personal.com"
                  aria-invalid={Boolean(errors.email)}
                  {...register('email')}
                />
              </Field>
              <Field id="sa-onb-designation" label="Designation" error={errors.designation?.message}>
                <Input
                  id="sa-onb-designation"
                  placeholder="Software Engineer"
                  aria-invalid={Boolean(errors.designation)}
                  {...register('designation')}
                />
              </Field>
              <Field id="sa-onb-doj" label="Date of joining" error={errors.dateOfJoining?.message}>
                <Input
                  id="sa-onb-doj"
                  type="date"
                  min={istTodayIso()}
                  aria-invalid={Boolean(errors.dateOfJoining)}
                  {...register('dateOfJoining')}
                />
              </Field>
              <div className="flex justify-end gap-2 pt-2">
                <Button type="button" variant="ghost" onClick={() => setOpen(false)}>
                  Cancel
                </Button>
                <Button type="submit" disabled={isSubmitting || teamHasNoHr}>
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
