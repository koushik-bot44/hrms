'use client';

import * as React from 'react';
import { useDropzone, type FileRejection } from 'react-dropzone';
import { toast } from 'sonner';
import { AlertCircle, CheckCircle2, FileText, Loader2, Paperclip, X } from 'lucide-react';
import {
  MAIL_ATTACHMENT_ACCEPT,
  MAIL_ATTACHMENT_MAX_BYTES,
  MAIL_ATTACHMENT_MAX_COUNT,
  formatFileSize,
  validateMailAttachment,
} from '@/lib/contract';
import { uploadMailAttachment } from '@/lib/api/mail';
import { ApiError } from '@/lib/api/client';
import { cn } from '@/lib/utils';
import { surface } from '@/components/ui/surface';
import { Button } from '@/components/ui/button';

export interface StagedAttachment {
  uid: string;
  name: string;
  size: number;
  progress: number;
  status: 'uploading' | 'done' | 'error';
  attachmentId?: string;
  error?: string;
}

/** The ids of attachments that finished uploading — ready to send with the message. */
export function stagedAttachmentIds(staged: StagedAttachment[]): string[] {
  return staged
    .filter((s) => s.status === 'done' && s.attachmentId)
    .map((s) => s.attachmentId as string);
}

/** Whether any staged attachment is still uploading (send should be disabled until they finish). */
export function attachmentsUploading(staged: StagedAttachment[]): boolean {
  return staged.some((s) => s.status === 'uploading');
}

/**
 * Attach files to a mail message (§8, Stage 4): a paperclip button + drag-drop, client-side
 * type/size/count validation, and per-file upload progress (the server re-validates on send). Uploads
 * go straight to storage via a presigned PUT; only the resulting draft ids are sent with the message.
 */
export function AttachmentPicker({
  staged,
  setStaged,
  disabled,
}: {
  staged: StagedAttachment[];
  setStaged: React.Dispatch<React.SetStateAction<StagedAttachment[]>>;
  disabled?: boolean;
}) {
  const full = staged.length >= MAIL_ATTACHMENT_MAX_COUNT;

  const onDrop = React.useCallback(
    (accepted: File[], rejections: FileRejection[]) => {
      if (rejections.length > 0) {
        toast.error(rejections[0]?.errors[0]?.message ?? 'File rejected');
      }
      const room = MAIL_ATTACHMENT_MAX_COUNT - staged.length;
      if (accepted.length > room) {
        toast.error(`At most ${MAIL_ATTACHMENT_MAX_COUNT} files per message`);
      }
      const toAdd = accepted.slice(0, Math.max(0, room)).map((file) => ({
        uid: crypto.randomUUID(),
        file,
        error: validateMailAttachment(file),
      }));

      setStaged((prev) => [
        ...prev,
        ...toAdd.map<StagedAttachment>(({ uid, file, error }) => ({
          uid,
          name: file.name,
          size: file.size,
          progress: 0,
          status: error ? 'error' : 'uploading',
          error: error ?? undefined,
        })),
      ]);

      // Start uploads OUTSIDE the state updater (so StrictMode's double-invoke can't double-upload).
      for (const { uid, file, error } of toAdd) {
        if (error) {
          toast.error(`${file.name}: ${error}`);
          continue;
        }
        uploadMailAttachment(file, (p) =>
          setStaged((s) => s.map((x) => (x.uid === uid ? { ...x, progress: p } : x))),
        )
          .then((attachmentId) =>
            setStaged((s) =>
              s.map((x) =>
                x.uid === uid ? { ...x, status: 'done', progress: 100, attachmentId } : x,
              ),
            ),
          )
          .catch((e) => {
            const message = e instanceof ApiError ? e.message : 'Upload failed';
            toast.error(`${file.name}: ${message}`);
            setStaged((s) =>
              s.map((x) => (x.uid === uid ? { ...x, status: 'error', error: message } : x)),
            );
          });
      }
    },
    [staged.length, setStaged],
  );

  const { getRootProps, getInputProps, isDragActive, open } = useDropzone({
    onDrop,
    accept: MAIL_ATTACHMENT_ACCEPT,
    maxSize: MAIL_ATTACHMENT_MAX_BYTES,
    noClick: true,
    noKeyboard: true,
    disabled: disabled || full,
  });

  const remove = (uid: string) => setStaged((s) => s.filter((x) => x.uid !== uid));

  return (
    <div {...getRootProps()} className={cn('rounded-md', isDragActive && 'ring-2 ring-primary ring-offset-1')}>
      <input {...getInputProps()} />
      <Button type="button" variant="ghost" size="sm" onClick={open} disabled={disabled || full}>
        <Paperclip />
        Attach
        {staged.length > 0 ? (
          <span className="text-muted-foreground">
            ({staged.length}/{MAIL_ATTACHMENT_MAX_COUNT})
          </span>
        ) : null}
      </Button>
      {staged.length > 0 ? (
        <ul className="mt-2 space-y-1.5">
          {staged.map((f) => (
            <li
              key={f.uid}
              className={cn(surface('card'), 'flex items-center gap-2 rounded-lg px-2.5 py-1.5 text-xs')}
            >
              <FileText className="size-4 shrink-0 text-muted-foreground" />
              <span className="min-w-0 flex-1 truncate">
                {f.name} <span className="text-muted-foreground">· {formatFileSize(f.size)}</span>
              </span>
              {f.status === 'uploading' ? (
                <span className="flex items-center gap-1 text-muted-foreground">
                  <Loader2 className="size-3.5 animate-spin" />
                  {f.progress}%
                </span>
              ) : null}
              {f.status === 'done' ? <CheckCircle2 className="size-4 text-success" /> : null}
              {f.status === 'error' ? (
                <AlertCircle className="size-4 text-destructive" aria-label={f.error} />
              ) : null}
              <button
                type="button"
                onClick={() => remove(f.uid)}
                aria-label={`Remove ${f.name}`}
                className="text-muted-foreground hover:text-foreground"
              >
                <X className="size-3.5" />
              </button>
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}
