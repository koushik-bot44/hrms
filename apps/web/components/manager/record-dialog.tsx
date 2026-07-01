'use client';

import * as React from 'react';
import { FileText } from 'lucide-react';
import { getApprovalRecord } from '@/lib/api/manager';
import { useApiQuery } from '@/lib/api/hooks';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog';
import { Skeleton } from '@/components/ui/skeleton';
import { RecordView } from '@/components/hr/record-view';

/** Read-only view of the forms + documents HR verified, opened from a manager approval. */
export function ManagerRecordDialog({
  approvalId,
  label,
}: {
  approvalId: string;
  label: string;
}) {
  const [open, setOpen] = React.useState(false);
  const query = useApiQuery(
    ['manager-approval-record', approvalId],
    (signal) => getApprovalRecord(approvalId, signal),
    { enabled: open, retry: false },
  );
  const record = query.data;

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button type="button" variant="outline" size="sm">
          <FileText className="size-4" />
          View record
        </Button>
      </DialogTrigger>
      <DialogContent className="max-h-[85vh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>{label}</DialogTitle>
          <DialogDescription>The sections and documents HR verified for this employee.</DialogDescription>
        </DialogHeader>
        {query.isLoading ? (
          <div className="space-y-3">
            <Skeleton className="h-32 w-full" />
            <Skeleton className="h-24 w-full" />
          </div>
        ) : query.isError ? (
          <p className="text-sm text-destructive">{query.error?.message ?? 'Could not load the record.'}</p>
        ) : record ? (
          <RecordView record={record} editable={false} />
        ) : null}
      </DialogContent>
    </Dialog>
  );
}
