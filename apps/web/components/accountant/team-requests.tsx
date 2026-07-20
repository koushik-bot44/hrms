'use client';

import * as React from 'react';
import { keepPreviousData, useQueryClient } from '@tanstack/react-query';
import type { ColumnDef } from '@tanstack/react-table';
import { useDropzone, type FileRejection } from 'react-dropzone';
import { toast } from 'sonner';
import { CheckCircle2, Download, FileText, Inbox, Loader2, Trash2, Upload, X } from 'lucide-react';
import {
  formatFileSize,
  MAIL_ATTACHMENT_ACCEPT,
  REQUEST_TYPE_LABELS,
  RequestStatus,
  validateMailAttachment,
  type RequestType,
  type TeamRequestRow,
} from '@/lib/contract';
import {
  getTeamRequests,
  openRequestDocument,
  pickUpRequest,
  requestKeys,
  resolveRequest,
  uploadRequestDocument,
} from '@/lib/api/requests';
import { useApiMutation, useApiQuery } from '@/lib/api/hooks';
import { cn } from '@/lib/utils';
import { DataTable } from '@/components/data-table';
import { StatusBadge } from '@/components/status-badge';
import { EmptyState } from '@/components/empty-state';
import { TableSkeleton } from '@/components/loading-skeleton';
import { Button } from '@/components/ui/button';
import { Progress } from '@/components/ui/progress';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';

const SELECT_CLASS =
  'h-9 rounded-md border border-input bg-background px-3 text-sm ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2';
const MAX_FILES = 20; // matches the server's per-request bind cap (§8d)

function formatDateTime(iso: string | null): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return Number.isNaN(d.getTime())
    ? iso
    : d.toLocaleString('en-IN', { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit' });
}

function typeLabel(t: RequestType): string {
  return REQUEST_TYPE_LABELS[t] ?? t;
}

/**
 * The team Accountant's request inbox (§8d): the requests routed to this accountant, filterable by status
 * (pending first). Pick up a submitted request, then upload the document(s) and resolve it — the API
 * scopes every read/act to this accountant's team, so other teams' requests never appear here.
 */
export function TeamRequests() {
  const queryClient = useQueryClient();
  const [status, setStatus] = React.useState<string>(RequestStatus.SUBMITTED); // pending first
  const [page, setPage] = React.useState(0);
  const [resolving, setResolving] = React.useState<TeamRequestRow | null>(null);

  const query = useApiQuery(
    requestKeys.team(status, page),
    (signal) => getTeamRequests({ status: status || undefined, page, size: 20 }, signal),
    { placeholderData: keepPreviousData },
  );
  const data = query.data;

  const refresh = () => void queryClient.invalidateQueries({ queryKey: ['requests', 'team'] });
  const pickUpMutation = useApiMutation((id: string) => pickUpRequest(id), {
    successMessage: 'Picked up — now in progress',
    onSettled: refresh,
  });

  const columns = React.useMemo<ColumnDef<TeamRequestRow>[]>(
    () => [
      {
        accessorKey: 'employeeName',
        header: 'Employee',
        cell: ({ row }) => (
          <div>
            <div className="font-medium">{row.original.employeeName ?? '—'}</div>
            <div className="font-mono text-xs text-muted-foreground">
              {row.original.employeeCode ?? '—'}
            </div>
          </div>
        ),
      },
      {
        accessorKey: 'teamName',
        header: 'Team',
        cell: ({ row }) => row.original.teamName ?? '—',
      },
      {
        accessorKey: 'requestType',
        header: 'Type',
        cell: ({ row }) => typeLabel(row.original.requestType),
      },
      {
        accessorKey: 'note',
        header: 'Note',
        cell: ({ row }) => (
          <span className="line-clamp-2 max-w-[16rem] text-muted-foreground">
            {row.original.note ?? '—'}
          </span>
        ),
      },
      {
        id: 'submitted',
        header: 'Submitted',
        cell: ({ row }) => (
          <span className="whitespace-nowrap tabular-nums text-muted-foreground">
            {formatDateTime(row.original.createdAt)}
          </span>
        ),
      },
      {
        accessorKey: 'status',
        header: 'Status',
        cell: ({ row }) => <StatusBadge status={row.original.status} />,
      },
      {
        id: 'actions',
        header: '',
        enableSorting: false,
        cell: ({ row }) => {
          const r = row.original;
          if (r.status === 'SUBMITTED' || r.status === 'IN_PROGRESS') {
            return (
              <div className="flex items-center justify-end gap-1.5">
                {r.status === 'SUBMITTED' ? (
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={pickUpMutation.isPending}
                    onClick={() => pickUpMutation.mutate(r.id)}
                  >
                    Pick up
                  </Button>
                ) : null}
                <Button variant="success" size="sm" onClick={() => setResolving(r)}>
                  <Upload />
                  Resolve
                </Button>
              </div>
            );
          }
          if (r.status === 'RESOLVED' && r.documents.length > 0) {
            return (
              <div className="flex flex-wrap items-center justify-end gap-1.5">
                {r.documents.map((doc) => (
                  <Button
                    key={doc.id}
                    variant="outline"
                    size="sm"
                    onClick={() => void openRequestDocument(r.id, doc.id)}
                  >
                    <Download className="size-4" />
                    <span className="max-w-[9rem] truncate">{doc.fileName}</span>
                  </Button>
                ))}
              </div>
            );
          }
          return null;
        },
      },
    ],
    [pickUpMutation],
  );

  const toolbar = (
    <select
      value={status}
      onChange={(e) => {
        setStatus(e.target.value);
        setPage(0);
      }}
      className={SELECT_CLASS}
      aria-label="Status filter"
    >
      <option value="">All statuses</option>
      {Object.values(RequestStatus).map((s) => (
        <option key={s} value={s}>
          {s.charAt(0) + s.slice(1).toLowerCase().replace('_', ' ')}
        </option>
      ))}
    </select>
  );

  return (
    <div className="space-y-3">
      {query.isLoading ? (
        <TableSkeleton rows={5} cols={7} />
      ) : query.isError ? (
        <EmptyState
          icon={Inbox}
          title="Couldn't load requests"
          description={query.error?.message ?? 'Please try again.'}
        />
      ) : (
        <>
          <DataTable
            columns={columns}
            data={data?.content ?? []}
            searchPlaceholder="Filter by name…"
            toolbar={toolbar}
            emptyState={
              <EmptyState
                icon={Inbox}
                title="No requests"
                description="Requests from your team will appear here."
              />
            }
          />
          {data && data.totalPages > 1 ? (
            <div className="flex items-center justify-between pt-1 text-sm text-muted-foreground">
              <span className="text-xs">
                Page {data.page + 1} of {data.totalPages} · {data.totalElements} request
                {data.totalElements === 1 ? '' : 's'}
              </span>
              <div className="flex gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  disabled={data.page === 0 || query.isFetching}
                  onClick={() => setPage((p) => Math.max(0, p - 1))}
                >
                  Previous
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={data.page >= data.totalPages - 1 || query.isFetching}
                  onClick={() => setPage((p) => p + 1)}
                >
                  Next
                </Button>
              </div>
            </div>
          ) : null}
        </>
      )}

      <ResolveDialog row={resolving} onClose={() => setResolving(null)} onResolved={refresh} />
    </div>
  );
}

type Staged = {
  key: string;
  name: string;
  size: number;
  status: 'uploading' | 'done' | 'error';
  progress: number;
  documentId?: string;
  error?: string;
};

function ResolveDialog({
  row,
  onClose,
  onResolved,
}: {
  row: TeamRequestRow | null;
  onClose: () => void;
  onResolved: () => void;
}) {
  const [staged, setStaged] = React.useState<Staged[]>([]);
  const [note, setNote] = React.useState('');
  const counter = React.useRef(0);

  React.useEffect(() => {
    if (row) {
      setStaged([]);
      setNote('');
      counter.current = 0;
    }
  }, [row]);

  const onDrop = React.useCallback(
    (accepted: File[], rejections: FileRejection[]) => {
      if (!row) return;
      if (rejections.length > 0) {
        toast.error(rejections[0]?.errors[0]?.message ?? 'Some files were rejected');
      }
      setStaged((prev) => {
        const room = MAX_FILES - prev.length;
        if (accepted.length > room) {
          toast.error(`You can attach at most ${MAX_FILES} files.`);
        }
        const next = [...prev];
        for (const file of accepted.slice(0, Math.max(0, room))) {
          const clientError = validateMailAttachment(file);
          const key = `f${counter.current++}`;
          if (clientError) {
            next.push({ key, name: file.name, size: file.size, status: 'error', progress: 0, error: clientError });
            continue;
          }
          next.push({ key, name: file.name, size: file.size, status: 'uploading', progress: 0 });
          void uploadRequestDocument(row.id, file, (percent) =>
            setStaged((s) => s.map((f) => (f.key === key ? { ...f, progress: percent } : f))),
          )
            .then((documentId) =>
              setStaged((s) =>
                s.map((f) => (f.key === key ? { ...f, status: 'done', progress: 100, documentId } : f)),
              ),
            )
            .catch((err) =>
              setStaged((s) =>
                s.map((f) =>
                  f.key === key
                    ? { ...f, status: 'error', error: err instanceof Error ? err.message : 'Upload failed' }
                    : f,
                ),
              ),
            );
        }
        return next;
      });
    },
    [row],
  );

  const { getRootProps, getInputProps, isDragActive } = useDropzone({
    onDrop,
    accept: MAIL_ATTACHMENT_ACCEPT,
    multiple: true,
  });

  const remove = (key: string) => setStaged((s) => s.filter((f) => f.key !== key));

  const uploading = staged.some((f) => f.status === 'uploading');
  const doneIds = staged.filter((f) => f.status === 'done' && f.documentId).map((f) => f.documentId as string);

  const resolveMutation = useApiMutation(
    () => resolveRequest(row!.id, { documentIds: doneIds, note: note.trim() || undefined }),
    {
      successMessage: 'Request resolved',
      onSuccess: () => {
        onClose();
        onResolved();
      },
    },
  );

  return (
    <Dialog open={Boolean(row)} onOpenChange={(o) => (!o ? onClose() : undefined)}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>Resolve request</DialogTitle>
          <DialogDescription>
            {row ? (
              <>
                Upload the {typeLabel(row.requestType).toLowerCase()} document(s) for{' '}
                {row.employeeName ?? 'this employee'}
                {row.note ? ` — “${row.note}”` : ''}, then resolve.
              </>
            ) : null}
          </DialogDescription>
        </DialogHeader>

        <div
          {...getRootProps()}
          className={cn(
            'flex cursor-pointer flex-col items-center justify-center gap-1 rounded-md border border-dashed p-6 text-center text-sm transition-colors',
            isDragActive ? 'border-primary bg-primary/5' : 'border-input hover:border-primary/50',
          )}
        >
          <input {...getInputProps()} />
          <Upload className="size-5 text-muted-foreground" aria-hidden />
          <p className="font-medium">Drop files here or click to browse</p>
          <p className="text-xs text-muted-foreground">PDF, images, or office docs · up to 10 MB each</p>
        </div>

        {staged.length > 0 ? (
          <ul className="space-y-2">
            {staged.map((f) => (
              <li key={f.key} className="flex items-center gap-3 rounded-md border p-2 text-sm">
                <FileText className="size-4 shrink-0 text-muted-foreground" aria-hidden />
                <div className="min-w-0 flex-1">
                  <div className="flex items-center justify-between gap-2">
                    <span className="truncate font-medium">{f.name}</span>
                    <span className="shrink-0 text-xs text-muted-foreground">{formatFileSize(f.size)}</span>
                  </div>
                  {f.status === 'uploading' ? (
                    <Progress value={f.progress} className="mt-1 h-1" />
                  ) : f.status === 'error' ? (
                    <p className="mt-0.5 text-xs text-destructive">{f.error}</p>
                  ) : (
                    <p className="mt-0.5 flex items-center gap-1 text-xs text-success">
                      <CheckCircle2 className="size-3" /> Uploaded
                    </p>
                  )}
                </div>
                {f.status === 'uploading' ? (
                  <Loader2 className="size-4 shrink-0 animate-spin text-muted-foreground" aria-hidden />
                ) : (
                  <button
                    type="button"
                    onClick={() => remove(f.key)}
                    className="shrink-0 text-muted-foreground hover:text-destructive"
                    aria-label={`Remove ${f.name}`}
                  >
                    {f.status === 'error' ? <X className="size-4" /> : <Trash2 className="size-4" />}
                  </button>
                )}
              </li>
            ))}
          </ul>
        ) : null}

        <textarea
          rows={2}
          value={note}
          onChange={(e) => setNote(e.target.value)}
          placeholder="Optional note for the employee"
          className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
        />

        <div className="flex justify-end gap-2 pt-1">
          <Button variant="ghost" onClick={onClose} disabled={resolveMutation.isPending}>
            Cancel
          </Button>
          <Button
            variant="success"
            disabled={resolveMutation.isPending || uploading || doneIds.length === 0}
            onClick={() => resolveMutation.mutate()}
          >
            {resolveMutation.isPending
              ? 'Resolving…'
              : uploading
                ? 'Uploading…'
                : `Resolve${doneIds.length ? ` (${doneIds.length})` : ''}`}
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
