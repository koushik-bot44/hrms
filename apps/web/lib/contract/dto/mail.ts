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

/** Compose a NEW thread. The recipient is chosen from `/mail/contacts` (graph-derived, never free-typed). */
export const SendMessageSchema = z.object({
  toUserId: z.string().min(1, 'Choose a recipient'),
  subject: z.string().trim().min(1, 'Subject is required').max(200, 'Subject is too long'),
  body: z.string().trim().min(1, 'Write a message').max(10000, 'Message is too long'),
});
export type SendMessageInput = z.infer<typeof SendMessageSchema>;

/** Reply within a thread — recipient is derived server-side (and re-checked by the graph). */
export const ReplyMessageSchema = z.object({
  body: z.string().trim().min(1, 'Write a reply').max(10000, 'Message is too long'),
});
export type ReplyMessageInput = z.infer<typeof ReplyMessageSchema>;

/**
 * Preview the address a local part will form in a domain (mirrors the backend's normalization) so
 * provisioning forms can show `localpart@domain` live. Returns `''` when the local part is empty.
 */
export function previewAddress(localPart: string, domain: string): string {
  const lp = (localPart ?? '').trim().toLowerCase();
  const d = (domain ?? '').trim().toLowerCase();
  return lp ? `${lp}@${d}` : '';
}
