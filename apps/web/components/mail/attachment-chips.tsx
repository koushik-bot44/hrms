'use client';

import { toast } from 'sonner';
import { Download, FileText } from 'lucide-react';
import type { MailAttachment } from '@/lib/contract';
import { formatFileSize } from '@/lib/contract';
import { getAttachmentDownloadUrl } from '@/lib/api/mail';
import { cn } from '@/lib/utils';
import { surface } from '@/components/ui/surface';

/**
 * Attachment chips shown under a message (§8, Stage 4). Clicking one resolves a short-lived,
 * participant-scoped presigned URL and opens it — the raw storage key is never exposed.
 */
export function AttachmentChips({ attachments }: { attachments: MailAttachment[] }) {
  if (!attachments || attachments.length === 0) return null;

  const download = async (id: string) => {
    try {
      const { url } = await getAttachmentDownloadUrl(id);
      window.open(url, '_blank', 'noopener');
    } catch {
      toast.error('Could not download the attachment');
    }
  };

  return (
    <div className="mt-2 flex flex-wrap gap-1.5">
      {attachments.map((a) => (
        <button
          key={a.id}
          type="button"
          onClick={() => download(a.id)}
          title={`Download ${a.fileName}`}
          className={cn(
            surface('card'),
            'flex items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-xs transition-colors hover:bg-accent',
          )}
        >
          <FileText className="size-3.5 shrink-0 text-muted-foreground" />
          <span className="max-w-[12rem] truncate">{a.fileName}</span>
          <span className="text-muted-foreground">{formatFileSize(a.sizeBytes)}</span>
          <Download className="size-3.5 shrink-0 text-muted-foreground" />
        </button>
      ))}
    </div>
  );
}
