/**
 * Pure logic for the inline in-document fill (§3.2/§3.6) — no DOM, no React, so it unit-tests under the
 * node-only vitest setup. The display bodyHtml carries stable field markers
 * (`<span data-field="KEY" data-kind="text|date|signature"></span>`); the InlineDocument component hydrates a
 * controlled input at each marker, joining `data-field` to the per-document field manifest (the source of
 * truth for kind/required/prefill). Here live: marker extraction, the manifest join + "unknown marker" guard,
 * value seeding, per-field validation (incl. the Aadhaar rule), and the family payload builders (which must
 * reproduce the exact shapes the complete/accept endpoints already take).
 */

/** The reserved data-field of the signature slot (never a real field key). */
export const SIGNATURE_FIELD = '__signature';

/** A manifest field (shape shared by offboarding FieldView + the new agreement fields). */
export interface DocField {
  key: string;
  label: string;
  kind: string; // TEXT | DATE | MULTILINE (case-insensitive)
  value: string | null;
  required: boolean;
}

export interface ParsedMarker {
  field: string;
  kind: string; // text | date | multiline | signature (lowercase, from data-kind)
}

const MARKER_RE = /<span\s+data-field="([^"]*)"\s+data-kind="([^"]*)"\s*>\s*<\/span>/g;

/** Extract every inline marker from the display bodyHtml, in document order. */
export function parseMarkers(html: string): ParsedMarker[] {
  const out: ParsedMarker[] = [];
  const re = new RegExp(MARKER_RE.source, 'g');
  let m: RegExpExecArray | null;
  while ((m = re.exec(html)) !== null) {
    out.push({ field: m[1], kind: m[2] });
  }
  return out;
}

/** Whether a marker is the signature slot. */
export function isSignatureMarker(m: ParsedMarker): boolean {
  return m.kind === 'signature' || m.field === SIGNATURE_FIELD;
}

/**
 * Field-marker keys that have NO entry in the manifest — these must fail loudly (a template/manifest drift
 * bug), never silently render an orphan blank. The signature marker is exempt.
 */
export function unknownMarkers(html: string, fields: Pick<DocField, 'key'>[]): string[] {
  const known = new Set(fields.map((f) => f.key));
  return parseMarkers(html)
    .filter((m) => !isSignatureMarker(m))
    .map((m) => m.field)
    .filter((key) => !known.has(key));
}

/** Seed the editable values from the manifest prefills (offboarding f.value / agreement prefill). */
export function seedValues(fields: DocField[]): Record<string, string> {
  const out: Record<string, string> = {};
  for (const f of fields) out[f.key] = f.value ?? '';
  return out;
}

/** Aadhaar = exactly 12 digits after stripping spaces/dashes. Mirrors the backend + the old isValidAadhaar. */
export function isValidAadhaar(raw: string): boolean {
  return /^\d{12}$/.test((raw ?? '').replace(/[\s-]/g, ''));
}

/**
 * The validation error for one field, or null when valid — the SAME rules as the old panel, relocated inline.
 * The Aadhaar field (key `aadhaar`, the AUP) is required + must be 12 digits; every other field is checked
 * only for required-non-blank (edited values otherwise fall back to their prefill server-side).
 */
export function fieldError(field: DocField, value: string): string | null {
  const v = (value ?? '').trim();
  if (field.key === 'aadhaar') {
    if (v.length === 0) return 'Enter your 12-digit Aadhaar number';
    if (!isValidAadhaar(v)) return 'Enter exactly 12 digits';
    return null;
  }
  if (field.required && v.length === 0) return `${field.label} is required`;
  return null;
}

/** Whether every field passes — the submit gate (combined with consent + scroll + signature by the component). */
export function allFieldsValid(fields: DocField[], values: Record<string, string>): boolean {
  return fields.every((f) => fieldError(f, values[f.key] ?? '') === null);
}

// --- Family payload builders (must equal the shapes the endpoints already accept) --------------------------

export type AgreementType = 'AUP' | 'NDA' | 'NOTICE_PERIOD';

/** CompleteAgreementRequest — per-type fields, undefined when not applicable (identical to the old panel). */
export function toAgreementComplete(
  type: AgreementType,
  values: Record<string, string>,
  signatureDataUrl: string,
) {
  return {
    consentAccepted: true as const,
    designation: values.designation || undefined,
    aadhaar: type === 'AUP' ? values.aadhaar : undefined,
    address: type === 'NDA' ? values.address : undefined,
    mobile: type === 'NDA' ? values.mobile : undefined,
    signatureDataUrl,
  };
}

/** CompleteDocRequest — the offboarding fillValues map keyed by the token/manifest key (unchanged). */
export function toOffboardingComplete(values: Record<string, string>, signatureDataUrl: string) {
  return { consentAccepted: true as const, fillValues: values, signatureDataUrl };
}

/** AcceptOfferRequest — the offer has no fill fields, only consent + signature (unchanged). */
export function toOfferAccept(signatureDataUrl: string) {
  return { consentAccepted: true as const, signatureDataUrl };
}
