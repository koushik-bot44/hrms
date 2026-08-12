/**
 * Pure staged-attachment helpers, kept in their OWN module (no react-dropzone) so the compose window can
 * import them synchronously while {@link AttachmentPicker} — the only react-dropzone user — is lazy-loaded.
 * Splitting these out is what lets react-dropzone stay off the /mail first load.
 */

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
