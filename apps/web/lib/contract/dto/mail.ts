import { z } from 'zod';

/** The platform mail domain (§8) — the Super Admin + Accounts Admin live on `@ihrms`. */
export const PLATFORM_MAIL_DOMAIN = 'ihrms';

/**
 * Internal-mail REQUEST contracts + the shared mailbox validators (ARCHITECTURE.md §8). Response
 * shapes (contacts, inbox, sent, message, unread-count) are derived from the Java OpenAPI schema in
 * `../responses.ts`; only the request/form zod schemas live here.
 *
 * A mailbox address is `localpart@domain` and IS the login email — so the same {@link MailLocalPartSchema}
 * (and {@link MailDomainSchema} for a company's domain) validate both the mail feature and the
 * provisioning forms that mint an address.
 */

/** A mailbox local part: lowercase letters/digits/dot/underscore/hyphen — no spaces or `@` (§8). */
export const MailLocalPartSchema = z
  .string()
  .trim()
  .toLowerCase()
  .min(1, 'Mailbox name is required')
  .max(64, 'Mailbox name is too long')
  .regex(
    /^[a-z0-9]([a-z0-9._-]*[a-z0-9])?$/,
    'Use letters, digits, dot, underscore or hyphen — no spaces or @',
  );

/** A company mail domain: lowercase letters/digits/hyphen — no spaces or `@` (§8). */
export const MailDomainSchema = z
  .string()
  .trim()
  .toLowerCase()
  .min(1, 'Mail domain is required')
  .max(63, 'Mail domain is too long')
  .regex(
    /^[a-z0-9]([a-z0-9-]*[a-z0-9])?$/,
    'Use lowercase letters, digits or hyphen — no spaces or @',
  );

/**
 * Compose a NEW thread with one or more recipients (§8). Recipients (TO/CC/BCC) are chosen from
 * `/mail/contacts` (graph-derived, never free-typed); at least one TO. The server re-checks EVERY
 * recipient and rejects the whole send if any is disallowed.
 */
export const SendMessageSchema = z.object({
  toUserIds: z.array(z.string()).min(1, 'Add at least one recipient'),
  ccUserIds: z.array(z.string()).optional(),
  bccUserIds: z.array(z.string()).optional(),
  subject: z.string().trim().min(1, 'Subject is required').max(200, 'Subject is too long'),
  body: z.string().trim().min(1, 'Write a message').max(10000, 'Message is too long'),
  // Ids of already-uploaded attachment drafts (§8); the server re-checks type/size/count on bind.
  attachmentIds: z.array(z.string()).max(5).optional(),
});
export type SendMessageInput = z.infer<typeof SendMessageSchema>;

/** Reply within a thread — recipient is derived server-side (and re-checked by the graph). */
export const ReplyMessageSchema = z.object({
  body: z.string().trim().min(1, 'Write a reply').max(10000, 'Message is too long'),
  attachmentIds: z.array(z.string()).max(5).optional(),
});
export type ReplyMessageInput = z.infer<typeof ReplyMessageSchema>;

// --- Attachment limits (§8, Stage 4) — mirror the server (which is authoritative) ------------

export const MAIL_ATTACHMENT_MAX_COUNT = 5;
export const MAIL_ATTACHMENT_MAX_BYTES = 10 * 1024 * 1024; // 10 MB

/** Allowed content types → extensions (for react-dropzone `accept` + validation). Executables excluded. */
export const MAIL_ATTACHMENT_ACCEPT: Record<string, string[]> = {
  'image/png': ['.png'],
  'image/jpeg': ['.jpg', '.jpeg'],
  'image/gif': ['.gif'],
  'image/webp': ['.webp'],
  'application/pdf': ['.pdf'],
  'text/plain': ['.txt'],
  'text/csv': ['.csv'],
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document': ['.docx'],
  'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet': ['.xlsx'],
  'application/vnd.openxmlformats-officedocument.presentationml.presentation': ['.pptx'],
  'application/zip': ['.zip'],
  'application/x-zip-compressed': ['.zip'],
};

/** Client-side pre-check (the server re-validates). Returns an error message, or null if allowed. */
export function validateMailAttachment(file: File): string | null {
  if (file.size <= 0) return 'File is empty';
  if (file.size > MAIL_ATTACHMENT_MAX_BYTES) return 'File is too large — the maximum is 10 MB';
  const type = (file.type || '').split(';')[0].trim().toLowerCase();
  const extensions = MAIL_ATTACHMENT_ACCEPT[type];
  if (!extensions) return 'This file type is not allowed. Executables and scripts cannot be attached.';
  const dot = file.name.lastIndexOf('.');
  const ext = dot >= 0 ? file.name.slice(dot).toLowerCase() : '';
  if (!extensions.includes(ext)) return 'The file name does not match its type.';
  return null;
}

/** Human-readable file size for chips (e.g. "2.4 MB"). */
export function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/**
 * Preview the address a local part will form in a domain (mirrors the backend's normalization) so
 * provisioning forms can show `localpart@domain` live. Returns `''` when the local part is empty.
 */
export function previewAddress(localPart: string, domain: string): string {
  const lp = (localPart ?? '').trim().toLowerCase();
  const d = (domain ?? '').trim().toLowerCase();
  return lp ? `${lp}@${d}` : '';
}
