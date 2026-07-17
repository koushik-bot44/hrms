'use client';

import * as React from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { ShieldCheck } from 'lucide-react';
import { getEmployeeRecord } from '@/lib/api/review';
import { useApiQuery } from '@/lib/api/hooks';
import { RecordView } from '@/components/hr/record-view';
import { EditEmployeeInfoDialog } from '@/components/employee-info/edit-employee-info-dialog';
import { EmptyState } from '@/components/empty-state';
import { LoadingSkeleton } from '@/components/loading-skeleton';

const recordKey = (id: string) => ['sa-employee-record', id] as const;

/**
 * The Super Admin's read-only forms-viewer for one employee (§2/§3.2): Forms 1/3/4 + the HR-authored
 * Form 2 + the generated PDFs (incl. the standalone HR-only Form-2 PDF). SA does not verify/route; the
 * only action is editing Form 2 while the employee is INVITED.
 */
export function SuperAdminEmployeeRecord({ employeeId }: { employeeId: string }) {
  const queryClient = useQueryClient();
  const query = useApiQuery(
    recordKey(employeeId),
    (signal) => getEmployeeRecord(employeeId, signal),
    { retry: false },
  );

  if (query.isLoading) return <LoadingSkeleton lines={8} />;
  if (query.isError || !query.data) {
    return (
      <EmptyState
        icon={ShieldCheck}
        title="Couldn't open this employee"
        description={
          query.error?.status === 404
            ? 'This employee could not be found.'
            : query.error?.message ?? 'Please try again.'
        }
      />
    );
  }

  const record = query.data;
  return (
    <RecordView
      record={record}
      editable={false}
      form2EditAction={
        record.status === 'INVITED' ? (
          <EditEmployeeInfoDialog
            employeeId={record.id}
            form2={record.form2}
            employeeCode={record.employeeCode}
            onSaved={() => queryClient.invalidateQueries({ queryKey: recordKey(employeeId) })}
          />
        ) : null
      }
    />
  );
}
