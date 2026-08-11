'use client';

import * as React from 'react';
import { CheckCircle2, ExternalLink, Maximize2, Minimize2, PenLine, RotateCcw } from 'lucide-react';
import { cn } from '@/lib/utils';
import { SignatureCapture } from '@/components/signature/signature-capture';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { DOCUMENT_BODY_CSS, INLINE_FIELD_CSS } from '@/components/documents/document-body-css';
import {
  allFieldsValid,
  fieldError,
  seedValues,
  unknownMarkers,
  type DocField,
} from '@/lib/documents/inline-markers';

/** Session key so a signature captured on one document carries to the next this session (agreements/offboarding). */
const SIG_STORE_KEY = 'document-signature';
/** Voidelements that never take children when rebuilt as React. */
const VOID_TAGS = new Set(['img', 'br', 'hr', 'input', 'col', 'wbr']);

/** The adopted signature PNG (Blob) → a data URL (matches the onboarding/agreement signature shape). */
export function blobToDataUrl(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result as string);
    reader.onerror = () => reject(reader.error);
    reader.readAsDataURL(blob);
  });
}

interface InlineCtx {
  values: Record<string, string>;
  setValue: (key: string, v: string) => void;
  specByKey: Record<string, DocField>;
  showErrors: boolean;
  signatureDataUrl: string | null;
  openSignature: () => void;
  clearSignature: () => void;
}
const Ctx = React.createContext<InlineCtx | null>(null);

/** One inline fill blank, controlled + sized to its content, with an inline error below when invalid. */
function InlineField({ fieldKey, kind }: { fieldKey: string; kind: string }) {
  const ctx = React.useContext(Ctx)!;
  const spec = ctx.specByKey[fieldKey];
  const value = ctx.values[fieldKey] ?? '';
  const error = spec ? fieldError(spec, value) : null;
  const invalid = ctx.showErrors && Boolean(error);
  // Size to content so the blank flows with the prose (min ~10ch so an empty blank is visibly there).
  const size = Math.max(fieldKey === 'aadhaar' ? 16 : 10, value.length + 2);
  return (
    <span className="inline-slot">
      <input
        className="inline-input"
        type={kind === 'date' ? 'date' : 'text'}
        inputMode={fieldKey === 'aadhaar' ? 'numeric' : undefined}
        size={size}
        value={value}
        aria-label={spec?.label ?? fieldKey}
        aria-invalid={invalid || undefined}
        placeholder={spec?.label}
        onChange={(e) => ctx.setValue(fieldKey, e.target.value)}
      />
      {invalid ? <span className="inline-error">{error}</span> : null}
    </span>
  );
}

/** The inline signature slot: "Sign here" until signed, then the signature image + a re-sign affordance. */
function InlineSignature() {
  const ctx = React.useContext(Ctx)!;
  if (ctx.signatureDataUrl) {
    return (
      <span className="inline-sign-wrap">
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img className="inline-sig" src={ctx.signatureDataUrl} alt="Your signature" />
        <button type="button" className="inline-resign" onClick={ctx.clearSignature}>
          re-sign
        </button>
      </span>
    );
  }
  return (
    <button type="button" className="inline-sign-btn" onClick={ctx.openSignature}>
      <PenLine className="size-3.5" />
      Sign here
    </button>
  );
}

// --- HTML → React (client-only; markers become the controlled components above) ---------------------------

function parseStyle(s: string): React.CSSProperties {
  const out: Record<string, string> = {};
  for (const decl of s.split(';')) {
    const i = decl.indexOf(':');
    if (i < 0) continue;
    const prop = decl.slice(0, i).trim();
    if (!prop) continue;
    out[prop.replace(/-([a-z])/g, (_, c: string) => c.toUpperCase())] = decl.slice(i + 1).trim();
  }
  return out as React.CSSProperties;
}

function convert(node: Node, key: string): React.ReactNode {
  if (node.nodeType === Node.TEXT_NODE) return node.textContent;
  if (node.nodeType !== Node.ELEMENT_NODE) return null;
  const el = node as Element;
  const field = el.getAttribute('data-field');
  if (field) {
    const kind = el.getAttribute('data-kind') ?? 'text';
    return kind === 'signature' ? (
      <InlineSignature key={key} />
    ) : (
      <InlineField key={key} fieldKey={field} kind={kind} />
    );
  }
  const tag = el.tagName.toLowerCase();
  const props: Record<string, unknown> = { key };
  for (const attr of Array.from(el.attributes)) {
    if (attr.name === 'class') props.className = attr.value;
    else if (attr.name === 'style') props.style = parseStyle(attr.value);
    else if (attr.name === 'colspan') props.colSpan = attr.value;
    else if (attr.name === 'rowspan') props.rowSpan = attr.value;
    else props[attr.name] = attr.value;
  }
  if (VOID_TAGS.has(tag)) return React.createElement(tag, props);
  const children = Array.from(el.childNodes).map((c, i) => convert(c, `${key}.${i}`));
  return React.createElement(tag, props, children);
}

function parseHtmlToReact(html: string): React.ReactNode {
  if (typeof window === 'undefined') return null;
  const doc = new DOMParser().parseFromString(`<div>${html}</div>`, 'text/html');
  const root = doc.body.firstElementChild;
  if (!root) return null;
  return Array.from(root.childNodes).map((c, i) => convert(c, `n${i}`));
}

// --- The shared read-and-sign screen ----------------------------------------------------------------------

export interface InlineDocumentProps {
  bodyHtml: string;
  fields: DocField[];
  /** Prefilled name shown in the signature "generate" mode. */
  fullName?: string;
  consentText: string;
  submitLabel: string;
  submitting: boolean;
  /** Already submitted — show the confirmation + download instead of the fillable body. */
  done: boolean;
  downloadUrl?: string | null;
  /** HR's send-back note (offboarding revision), shown above the document. */
  revisionNote?: string | null;
  /** Offer a "use the signature you captured earlier" option this session (agreements/offboarding, not offer). */
  reuseSignature?: boolean;
  onSubmit: (values: Record<string, string>, signatureDataUrl: string) => void;
  /** Extra content for the confirmation card (e.g. a done message). */
  doneTitle?: string;
  doneDescription?: React.ReactNode;
}

export function InlineDocument({
  bodyHtml,
  fields,
  fullName = '',
  consentText,
  submitLabel,
  submitting,
  done,
  downloadUrl,
  revisionNote,
  reuseSignature = false,
  onSubmit,
  doneTitle = 'Signed and submitted',
  doneDescription,
}: InlineDocumentProps) {
  const [mounted, setMounted] = React.useState(false);
  const [values, setValues] = React.useState<Record<string, string>>(() => seedValues(fields));
  const [signatureDataUrl, setSignatureDataUrl] = React.useState<string | null>(null);
  const [previousSignature, setPreviousSignature] = React.useState<string | null>(null);
  const [consent, setConsent] = React.useState(false);
  const [scrolledEnd, setScrolledEnd] = React.useState(false);
  const [showErrors, setShowErrors] = React.useState(false);
  const [capturing, setCapturing] = React.useState(false);
  const [expanded, setExpanded] = React.useState(false); // full-screen document reader
  const scrollRef = React.useRef<HTMLDivElement>(null);

  React.useEffect(() => setMounted(true), []);
  // Re-seed when the manifest changes — a sent-back document reopens with its prior values in fields[].value.
  React.useEffect(() => setValues(seedValues(fields)), [fields]);
  // Reuse-within-flow: offer the signature captured earlier this session.
  React.useEffect(() => {
    if (!reuseSignature) return;
    try {
      setPreviousSignature(sessionStorage.getItem(SIG_STORE_KEY));
    } catch {
      /* sessionStorage unavailable */
    }
  }, [reuseSignature]);
  // Dev guard: a marker with no manifest entry is a template/manifest drift bug — surface it loudly.
  React.useEffect(() => {
    const unknown = unknownMarkers(bodyHtml, fields);
    if (unknown.length) console.error('InlineDocument: markers with no manifest field:', unknown);
  }, [bodyHtml, fields]);

  const checkScrollEnd = React.useCallback(() => {
    const el = scrollRef.current;
    if (!el) return;
    if (el.scrollHeight - el.scrollTop - el.clientHeight < 24) setScrolledEnd(true);
  }, []);
  React.useEffect(() => {
    if (done) return;
    const id = window.setTimeout(checkScrollEnd, 60); // a short doc that needs no scroll counts as read
    return () => window.clearTimeout(id);
  }, [done, checkScrollEnd, mounted]);

  const specByKey = React.useMemo(
    () => Object.fromEntries(fields.map((f) => [f.key, f])),
    [fields],
  );
  const parsed = React.useMemo(() => (mounted ? parseHtmlToReact(bodyHtml) : null), [mounted, bodyHtml]);

  const adoptSignature = React.useCallback(
    async (blob: Blob) => {
      const url = await blobToDataUrl(blob);
      setSignatureDataUrl(url);
      setCapturing(false);
      if (reuseSignature) {
        try {
          sessionStorage.setItem(SIG_STORE_KEY, url);
          setPreviousSignature(url);
        } catch {
          /* ignore */
        }
      }
    },
    [reuseSignature],
  );

  const valid = allFieldsValid(fields, values);
  const canSubmit = !done && scrolledEnd && consent && Boolean(signatureDataUrl) && valid && !submitting;
  const missingSignature = !signatureDataUrl;

  const submit = () => {
    setShowErrors(true);
    if (canSubmit) onSubmit(values, signatureDataUrl!);
  };

  if (done) {
    return (
      <Card className="border-success/30 bg-success/5">
        <CardContent className="flex flex-wrap items-center gap-3 py-5">
          <CheckCircle2 className="size-5 text-success" />
          <div className="min-w-0 flex-1">
            <div className="font-medium">{doneTitle}</div>
            {doneDescription ? (
              <p className="text-sm text-muted-foreground">{doneDescription}</p>
            ) : null}
          </div>
          {downloadUrl ? (
            <a href={downloadUrl} target="_blank" rel="noreferrer">
              <Button type="button" variant="outline" size="sm">
                <ExternalLink />
                View signed PDF
              </Button>
            </a>
          ) : null}
        </CardContent>
      </Card>
    );
  }

  const ctx: InlineCtx = {
    values,
    setValue: (key, v) => setValues((prev) => ({ ...prev, [key]: v })),
    specByKey,
    showErrors,
    signatureDataUrl,
    openSignature: () => setCapturing(true),
    clearSignature: () => setSignatureDataUrl(null),
  };

  // The document body pane — rendered in EXACTLY ONE place at a time (compact card OR the full-screen dialog),
  // never both, so its inline fields + signature are never duplicated. All fill state (values, signature,
  // scrolledEnd) lives in this component's state and the controlled inputs re-hydrate from it on any remount,
  // so toggling full-screen never loses entered values or the scroll-to-consent gate.
  const bodyPane = (paneClassName: string) => (
    <div
      ref={scrollRef}
      onScroll={checkScrollEnd}
      className={cn('agreement-body overflow-y-auto rounded-md border bg-background p-5', paneClassName)}
    >
      {mounted ? (
        <Ctx.Provider value={ctx}>{parsed}</Ctx.Provider>
      ) : (
        <div dangerouslySetInnerHTML={{ __html: bodyHtml }} />
      )}
    </div>
  );

  return (
    <div className="space-y-6">
      <style dangerouslySetInnerHTML={{ __html: DOCUMENT_BODY_CSS + INLINE_FIELD_CSS }} />

      {revisionNote ? (
        <Card className="border-warning/40 bg-warning/5">
          <CardContent className="py-4 text-sm">
            <p className="font-medium">HR sent this back for changes</p>
            <p className="mt-1 text-muted-foreground">{revisionNote}</p>
          </CardContent>
        </Card>
      ) : null}

      <Card>
        <CardHeader className="flex-col gap-2 sm:flex-row sm:items-center sm:justify-between sm:space-y-0">
          <CardTitle className="text-base">
            Read the full document — fill the blanks and sign inside it
          </CardTitle>
          <Button type="button" variant="outline" size="sm" onClick={() => setExpanded(true)}>
            <Maximize2 className="size-4" />
            View full document
          </Button>
        </CardHeader>
        <CardContent>
          {expanded ? (
            // The body is open in the full-screen reader below — keep the compact pane out of the DOM so the
            // fields aren't duplicated. (This placeholder sits behind the full-screen overlay.)
            <div className="flex flex-col items-center gap-2 rounded-md border border-dashed bg-muted/30 py-10 text-center text-sm text-muted-foreground">
              <Maximize2 className="size-5" />
              Open in full screen.
            </div>
          ) : (
            bodyPane('max-h-[65vh]')
          )}
          {!scrolledEnd ? (
            <p className="mt-2 text-xs text-muted-foreground">Scroll to the end of the document to continue.</p>
          ) : null}
        </CardContent>
      </Card>

      {/* Full-screen reader — the SAME body pane (fields + signature functional); genuinely full-screen on
          mobile, a large sheet on desktop. Closing returns to the compact view with all values + the
          scroll-to-consent state intact (state lives in this component). */}
      <Dialog open={expanded} onOpenChange={setExpanded}>
        <DialogContent className="flex h-[100dvh] max-h-[100dvh] w-screen max-w-[100vw] flex-col gap-3 rounded-none p-4 sm:h-[92dvh] sm:max-h-[92dvh] sm:w-[94vw] sm:max-w-5xl sm:rounded-lg sm:p-6">
          <DialogHeader className="space-y-0 pr-8 text-left">
            <DialogTitle className="text-base">Full document — fill the blanks and sign inside it</DialogTitle>
            {!scrolledEnd ? (
              <DialogDescription>Scroll to the end of the document to continue.</DialogDescription>
            ) : null}
          </DialogHeader>
          {bodyPane('min-h-0 flex-1')}
          <div className="flex justify-end">
            <Button type="button" variant="outline" size="sm" onClick={() => setExpanded(false)}>
              <Minimize2 className="size-4" />
              Done
            </Button>
          </div>
        </DialogContent>
      </Dialog>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">Consent &amp; submit</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <label
            className="flex items-start gap-3 rounded-md border p-3 text-sm has-[:disabled]:opacity-60"
            htmlFor="inline-consent"
          >
            <input
              id="inline-consent"
              type="checkbox"
              className="mt-0.5 size-4"
              checked={consent}
              disabled={!scrolledEnd}
              onChange={(e) => setConsent(e.target.checked)}
            />
            <span>
              {consentText}
              {!scrolledEnd ? (
                <span className="block text-xs text-muted-foreground">
                  Scroll to the end of the document above to enable this.
                </span>
              ) : null}
            </span>
          </label>

          {missingSignature ? (
            <p className="text-sm text-muted-foreground">
              Sign at the <span className="font-medium">signature line</span> inside the document above.
            </p>
          ) : (
            <p className="flex items-center gap-2 text-sm text-success">
              <CheckCircle2 className="size-4" /> Signed
              <button
                type="button"
                className="text-xs text-muted-foreground underline"
                onClick={() => setSignatureDataUrl(null)}
              >
                <RotateCcw className="mr-1 inline size-3" />
                redo
              </button>
            </p>
          )}

          {showErrors && !valid ? (
            <p className="text-sm text-destructive">Fix the highlighted blanks in the document before submitting.</p>
          ) : null}

          <div className="flex items-center justify-end">
            <Button type="button" onClick={submit} disabled={!canSubmit}>
              {submitting ? 'Submitting…' : submitLabel}
            </Button>
          </div>
        </CardContent>
      </Card>

      <Dialog open={capturing} onOpenChange={setCapturing}>
        <DialogContent className="max-h-[85vh] overflow-y-auto sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>Add your signature</DialogTitle>
            <DialogDescription>Draw, type, or upload — it stamps at the signature line.</DialogDescription>
          </DialogHeader>
          {reuseSignature && previousSignature ? (
            <div className="flex flex-wrap items-center gap-3 rounded-md border border-dashed p-3">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img
                src={previousSignature}
                alt="Signature you captured earlier"
                className="h-12 w-auto max-w-[200px] bg-white"
              />
              <span className="text-sm text-muted-foreground">Use the signature you captured earlier?</span>
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => {
                  setSignatureDataUrl(previousSignature);
                  setCapturing(false);
                }}
              >
                Use this signature
              </Button>
            </div>
          ) : null}
          <SignatureCapture fullName={fullName} onAdopt={(blob) => void adoptSignature(blob)} />
        </DialogContent>
      </Dialog>
    </div>
  );
}
