'use client';

import * as React from 'react';
import { useDropzone, type FileRejection } from 'react-dropzone';
import { useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { ExternalLink, FileText, Loader2, Trash2, Upload } from 'lucide-react';
import {
  DOCUMENT_TYPE_LABELS,
  MAX_UPLOAD_BYTES,
  maxDocumentsForType,
  type DocumentDto,
  type DocumentType,
} from '@/lib/contract';
import { deleteDocument, getDocumentViewUrl, uploadDocument } from '@/lib/api/onboarding';
import { ApiError } from '@/lib/api/client';
import { cn } from '@/lib/utils';
import { surface } from '@/components/ui/surface';
import { StatusBadge } from '@/components/status-badge';
import { Progress } from '@/components/ui/progress';
import { Button } from '@/components/ui/button';

/** One Form 4 upload slot (docType + optional employment groupIndex 1..4). */
export function DocumentUploader({
  docType,
  groupIndex = null,
  label,
  required,
  documents,
  disabled,
}: {
  docType: DocumentType;
  groupIndex?: number | null;
  label?: string;
  required?: boolean;
  documents: DocumentDto[];
  disabled?: boolean;
}) {
  const queryClient = useQueryClient();
  const [progress, setProgress] = React.useState<number | null>(null);
  const [removingId, setRemovingId] = React.useState<string | null>(null);
  const mine = documents.filter(
    (d) => d.docType === docType && (d.groupIndex ?? null) === groupIndex,
  );
  const maxFilesForSlot = maxDocumentsForType(docType); // 2 for Aadhaar/PAN, 1 otherwise
  const limitReached = mine.length >= maxFilesForSlot;

  const onDrop = React.useCallback(
    async (accepted: File[], rejections: FileRejection[]) => {
      if (rejections.length > 0) {
        toast.error(rejections[0]?.errors[0]?.message ?? 'File rejected');
        return;
      }
      const file = accepted[0];
      if (!file) return;
      setProgress(0);
      try {
        await uploadDocument(file, docType, groupIndex, setProgress);
        toast.success(`${file.name} uploaded`);
        await queryClient.invalidateQueries({ queryKey: ['onboarding'] });
      } catch (error) {
        toast.error(error instanceof ApiError ? error.message : 'Upload failed');
      } finally {
        setProgress(null);
      }
    },
    [docType, groupIndex, queryClient],
  );

  const { getRootProps, getInputProps, isDragActive } = useDropzone({
    onDrop,
    maxFiles: 1,
    maxSize: MAX_UPLOAD_BYTES,
    accept: { 'application/pdf': ['.pdf'], 'image/png': ['.png'], 'image/jpeg': ['.jpg', '.jpeg'] },
    disabled: disabled || progress !== null || limitReached,
  });

  const view = async (id: string) => {
    try {
      const { url } = await getDocumentViewUrl(id);
      window.open(url, '_blank', 'noopener');
    } catch {
      toast.error('Could not open the document');
    }
  };

  const remove = async (id: string) => {
    setRemovingId(id);
    try {
      await deleteDocument(id);
      toast.success('Document removed');
      await queryClient.invalidateQueries({ queryKey: ['onboarding'] });
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Could not remove the document');
    } finally {
      setRemovingId(null);
    }
  };

  return (
    <div className="space-y-2">
      <span className="flex items-center gap-2 text-sm font-medium">
        {label ?? DOCUMENT_TYPE_LABELS[docType]}
        {required ? <span className="text-destructive">*</span> : null}
        <span className="text-xs font-normal text-muted-foreground">
          {mine.length}/{maxFilesForSlot}
        </span>
      </span>

      {!disabled && !limitReached ? (
        <div
          {...getRootProps()}
          className={cn(
            'flex cursor-pointer flex-col items-center justify-center gap-1.5 rounded-xl border border-dashed px-4 py-5 text-center text-sm transition-colors',
            isDragActive ? 'border-primary bg-primary/5' : 'bg-muted/30 hover:border-primary/50',
            progress !== null && 'pointer-events-none opacity-70',
          )}
        >
          <input {...getInputProps()} />
          {progress !== null ? (
            <div className="w-full space-y-2">
              <div className="flex items-center justify-center gap-2 text-muted-foreground">
                <Loader2 className="size-4 animate-spin" />
                Uploading… {progress}%
              </div>
              <Progress value={progress} />
            </div>
          ) : (
            <>
              <Upload className="size-5 text-muted-foreground" />
              <span className="text-muted-foreground">
                Drag &amp; drop or <span className="font-medium text-primary">browse</span>
              </span>
              <span className="text-xs text-muted-foreground">PDF, PNG or JPEG · max 10MB</span>
            </>
          )}
        </div>
      ) : null}

      {!disabled && limitReached ? (
        <p className="rounded-xl border border-dashed px-3 py-2 text-center text-xs text-muted-foreground">
          {maxFilesForSlot === 1
            ? 'A file is uploaded — remove it to upload a different one.'
            : `Maximum of ${maxFilesForSlot} files reached — remove one to upload a different file.`}
        </p>
      ) : null}

      {mine.length > 0 ? (
        <ul className="space-y-1.5">
          {mine.map((doc) => (
            <li
              key={doc.id}
              className={cn(surface('card'), 'flex items-center justify-between gap-2 rounded-lg px-3 py-2 text-sm')}
            >
              <span className="flex min-w-0 items-center gap-2">
                <FileText className="size-4 shrink-0 text-muted-foreground" />
                <span className="truncate">{doc.fileName}</span>
              </span>
              <span className="flex shrink-0 items-center gap-2">
                <StatusBadge status={doc.status} />
                <Button type="button" variant="ghost" size="icon" onClick={() => view(doc.id)} aria-label="View document">
                  <ExternalLink className="size-4" />
                </Button>
                {!disabled ? (
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon"
                    onClick={() => remove(doc.id)}
                    disabled={removingId === doc.id}
                    aria-label="Remove document"
                  >
                    {removingId === doc.id ? (
                      <Loader2 className="size-4 animate-spin" />
                    ) : (
                      <Trash2 className="size-4 text-destructive" />
                    )}
                  </Button>
                ) : null}
              </span>
            </li>
          ))}
        </ul>
      ) : disabled ? (
        <p className="text-sm text-muted-foreground">No document uploaded.</p>
      ) : null}
    </div>
  );
}
