'use client';

import * as React from 'react';
import { useDropzone, type FileRejection } from 'react-dropzone';
import { useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { ExternalLink, FileText, Loader2, Trash2, Upload } from 'lucide-react';
import {
  MAX_DOCUMENTS_PER_TYPE,
  MAX_UPLOAD_BYTES,
  type DocumentDto,
  type DocumentType,
  type SectionKey,
} from '@/lib/contract';
import { deleteDocument, getDocumentViewUrl, uploadDocument } from '@/lib/api/onboarding';
import { ApiError } from '@/lib/api/client';
import { cn } from '@/lib/utils';
import { StatusBadge } from '@/components/status-badge';
import { Progress } from '@/components/ui/progress';
import { Button } from '@/components/ui/button';

export const DOC_TYPE_LABELS: Record<DocumentType, string> = {
  EXPERIENCE_LETTER: 'Experience letter',
  PAN: 'PAN card',
  AADHAAR: 'Aadhaar',
  BGV_DOCUMENT: 'Background verification',
  OTHER: 'Other document',
};

export function DocumentUploader({
  sectionKey,
  docType,
  required,
  documents,
  disabled,
}: {
  sectionKey: SectionKey;
  docType: DocumentType;
  required?: boolean;
  documents: DocumentDto[];
  disabled?: boolean;
}) {
  const queryClient = useQueryClient();
  const [progress, setProgress] = React.useState<number | null>(null);
  const [removingId, setRemovingId] = React.useState<string | null>(null);
  const mine = documents.filter((d) => d.sectionKey === sectionKey && d.docType === docType);
  const limitReached = mine.length >= MAX_DOCUMENTS_PER_TYPE;

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
        await uploadDocument(file, sectionKey, docType, setProgress);
        toast.success(`${file.name} uploaded`);
        await queryClient.invalidateQueries({ queryKey: ['onboarding'] });
      } catch (error) {
        toast.error(error instanceof ApiError ? error.message : 'Upload failed');
      } finally {
        setProgress(null);
      }
    },
    [sectionKey, docType, queryClient],
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
        {DOC_TYPE_LABELS[docType]}
        {required ? <span className="text-destructive">*</span> : null}
        <span className="text-xs font-normal text-muted-foreground">
          {mine.length}/{MAX_DOCUMENTS_PER_TYPE}
        </span>
      </span>

      {!disabled && !limitReached ? (
        <div
          {...getRootProps()}
          className={cn(
            'flex cursor-pointer flex-col items-center justify-center gap-1.5 rounded-md border border-dashed px-4 py-6 text-center text-sm transition-colors',
            isDragActive ? 'border-primary bg-primary/5' : 'hover:border-primary/50',
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
              <span className="text-xs text-muted-foreground">
                PDF, PNG or JPEG · max 10MB · up to {MAX_DOCUMENTS_PER_TYPE} files (e.g. front &amp; back)
              </span>
            </>
          )}
        </div>
      ) : null}

      {!disabled && limitReached ? (
        <p className="rounded-md border border-dashed px-3 py-2 text-center text-xs text-muted-foreground">
          Maximum of {MAX_DOCUMENTS_PER_TYPE} files reached — remove one to upload a different file.
        </p>
      ) : null}

      {mine.length > 0 ? (
        <ul className="space-y-1.5">
          {mine.map((doc) => (
            <li
              key={doc.id}
              className="flex items-center justify-between gap-2 rounded-md border bg-card px-3 py-2 text-sm"
            >
              <span className="flex min-w-0 items-center gap-2">
                <FileText className="size-4 shrink-0 text-muted-foreground" />
                <span className="truncate">{doc.fileName}</span>
              </span>
              <span className="flex shrink-0 items-center gap-2">
                <StatusBadge status={doc.status} />
                <Button
                  type="button"
                  variant="ghost"
                  size="icon"
                  onClick={() => view(doc.id)}
                  aria-label="View document"
                >
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
