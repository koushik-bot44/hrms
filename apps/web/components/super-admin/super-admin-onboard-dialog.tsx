'use client';

import * as React from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useQueryClient } from '@tanstack/react-query';
import { UserPlus } from 'lucide-react';
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
import { EmployeeInfoFields } from '@/components/employee-info/employee-info-fields';
import { OnboardedConfirmation } from '@/components/hr/onboard-employee-dialog';

const SELECT_CLASS =
  'flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50';

const EMPTY: SuperAdminOnboardInput = {
  companyId: '',
  teamId: '',
  fullName: '',
  personalEmail: '',
  designation: '',
  dateOfJoining: '',
  officialEmail: '',
};

/** Super Admin onboards into any company (§2): pick company → team (→ that team's HR) → fill Form 2. */
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
    defaultValues: EMPTY,
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
    (values: SuperAdminOnboardInput) => {
      const { companyId: cid, teamId: tid, ...form2 } = values;
      return onboardForCompany(cid, { teamId: tid, form2 });
    },
    {
      successMessage: (data) => `${data.employee.fullName} onboarded`,
      onSuccess: (data) => {
        setResult({ email: data.employee.email });
        reset(EMPTY);
        void queryClient.invalidateQueries({ queryKey: ['company', companyId] });
        void queryClient.invalidateQueries({ queryKey: ['companies'] });
        void queryClient.invalidateQueries({ queryKey: ['company-employees', companyId] });
        void queryClient.invalidateQueries({ queryKey: ['dashboard'] });
      },
      onError: (error) => {
        if (error.status === 400 || error.status === 409) {
          setError('personalEmail', { message: error.message });
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
    reset(EMPTY);
  };

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        if (!next) {
          setResult(null);
          reset(EMPTY);
        }
      }}
    >
      <DialogTrigger asChild>
        <Button size="sm" variant="outline">
          <UserPlus />
          Onboard employee
        </Button>
      </DialogTrigger>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-2xl">
        {result ? (
          <OnboardedConfirmation email={result.email} onAgain={() => setResult(null)} onDone={close} />
        ) : (
          <>
            <DialogHeader>
              <DialogTitle>Onboard an employee</DialogTitle>
              <DialogDescription>
                Choose the company and team — the employee attaches to that team&apos;s HR — then fill
                their Employee Info (Form 2).
              </DialogDescription>
            </DialogHeader>
            <form onSubmit={onSubmit} className="space-y-6" noValidate>
              <section className="space-y-3">
                <h3 className="text-sm font-semibold">Company &amp; team</h3>
                <div className="grid gap-4 sm:grid-cols-2">
                  <FieldWrap id="sa-onb-company" label="Company" error={errors.companyId?.message} required>
                    <select
                      id="sa-onb-company"
                      className={SELECT_CLASS}
                      aria-invalid={Boolean(errors.companyId)}
                      {...register('companyId')}
                      onChange={(e) => {
                        setValue('companyId', e.target.value, { shouldValidate: true });
                        setValue('teamId', '');
                      }}
                    >
                      <option value="">Select a company…</option>
                      {(companies.data ?? []).map((c) => (
                        <option key={c.id} value={c.id}>
                          {c.name} ({c.code})
                        </option>
                      ))}
                    </select>
                  </FieldWrap>

                  <FieldWrap id="sa-onb-team" label="Team" error={errors.teamId?.message} required>
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
                  </FieldWrap>
                </div>

                {teamId ? (
                  <div className="rounded-md border bg-muted/30 px-3 py-2 text-sm">
                    <span className="text-muted-foreground">Onboarding HR: </span>
                    {derivedHr ? (
                      <span className="font-medium">
                        {derivedHr.name}{' '}
                        <span className="text-muted-foreground">· {derivedHr.email}</span>
                      </span>
                    ) : (
                      <span className="text-destructive">
                        This team has no HR assigned — assign one before onboarding.
                      </span>
                    )}
                  </div>
                ) : null}
              </section>

              <EmployeeInfoFields
                register={register}
                errors={errors}
                dojMin={istTodayIso()}
                idPrefix="sa-onb"
              />

              <div className="flex justify-end gap-2">
                <Button type="button" variant="ghost" onClick={() => setOpen(false)}>
                  Cancel
                </Button>
                <Button type="submit" disabled={isSubmitting || mutation.isPending || teamHasNoHr}>
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

function FieldWrap({
  id,
  label,
  error,
  required,
  children,
}: {
  id: string;
  label: string;
  error?: string;
  required?: boolean;
  children: React.ReactNode;
}) {
  return (
    <div className="space-y-1.5">
      <label htmlFor={id} className="text-sm font-medium">
        {label}
        {required ? <span className="ml-0.5 text-destructive">*</span> : null}
      </label>
      {children}
      {error ? <p className="text-xs text-destructive">{error}</p> : null}
    </div>
  );
}
