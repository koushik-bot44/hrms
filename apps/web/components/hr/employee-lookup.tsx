'use client';

import * as React from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Search, ShieldCheck } from 'lucide-react';
import { EmployeeLookupSchema, type EmployeeLookupInput } from '@/lib/contract';
import { lookupEmployeeByCode } from '@/lib/api/review';
import { useApiQuery } from '@/lib/api/hooks';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { EmptyState } from '@/components/empty-state';
import { RecordView } from '@/components/hr/record-view';

/** §3.4 records lookup by employee ID — read-only, resolves approved employees only. */
export function EmployeeLookup() {
  const [code, setCode] = React.useState<string | null>(null);
  const lookup = useForm<EmployeeLookupInput>({
    resolver: zodResolver(EmployeeLookupSchema),
    defaultValues: { employeeCode: '' },
  });

  const query = useApiQuery(
    code ? ['hr-lookup', code] : ['hr-lookup', 'idle'],
    (signal) => lookupEmployeeByCode(code as string, signal),
    { enabled: Boolean(code), retry: false },
  );

  const onLookup = lookup.handleSubmit((v) => setCode(v.employeeCode));
  const record = query.data;

  return (
    <div className="space-y-6">
      <form onSubmit={onLookup} className="flex flex-col gap-2 sm:flex-row sm:items-start" noValidate>
        <div className="flex-1 space-y-1.5">
          <label htmlFor="lookup" className="sr-only">
            Employee ID
          </label>
          <Input
            id="lookup"
            placeholder="Enter an approved employee's ID, e.g. ACME-EMP-000123"
            className="font-mono"
            autoComplete="off"
            aria-invalid={Boolean(lookup.formState.errors.employeeCode)}
            {...lookup.register('employeeCode')}
          />
          {lookup.formState.errors.employeeCode ? (
            <p className="text-xs text-destructive">{lookup.formState.errors.employeeCode.message}</p>
          ) : null}
        </div>
        <Button type="submit">
          <Search />
          Look up
        </Button>
      </form>

      {!code ? (
        <EmptyState
          icon={ShieldCheck}
          title="Look up an approved employee"
          description="Enter an employee ID to view their full record (read-only). IDs are assigned on approval."
        />
      ) : null}

      {code && query.isLoading ? <Skeleton className="h-40 w-full" /> : null}

      {code && query.isError ? (
        <EmptyState
          icon={ShieldCheck}
          title="No matching employee"
          description={
            query.error?.status === 404
              ? 'No approved employee with that ID in your workspace.'
              : query.error?.message ?? 'Could not load the record.'
          }
        />
      ) : null}

      {record ? <RecordView record={record} editable={false} /> : null}
    </div>
  );
}
