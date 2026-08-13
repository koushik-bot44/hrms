'use client';

import * as React from 'react';
import { FileText, RotateCcw, Save, Trash2, Upload } from 'lucide-react';
import { toast } from 'sonner';
import type { Letterhead, LetterheadMargins } from '@/lib/contract';
import { LETTERHEAD_ACCEPT_PDF, LETTERHEAD_ACCEPT_WORD } from '@/lib/contract';
import {
  getLetterhead,
  removeLetterhead,
  resetLetterheadMargins,
  saveLetterheadMargins,
  uploadLetterhead,
} from '@/lib/api/companies';
import { useApiQuery } from '@/lib/api/hooks';
import { ApiError } from '@/lib/api/client';
import { LoadingSkeleton } from '@/components/loading-skeleton';
import { EmptyState } from '@/components/empty-state';
import { Button } from '@/components/ui/button';

/**
 * SUPER_ADMIN letterhead management for one company (§3.5, document model). The admin uploads ONE letterhead
 * file (PDF, or Word when the server can convert), then sets the content margins by dragging guides over a
 * live preview of the page — exactly like adjusting margins in Word. Documents generated from then on print
 * onto the letterhead within those margins; existing PDFs are unchanged, and the onboarding forms are never
 * branded.
 */
const PT_TO_MM = 25.4 / 72;
const mmLabel = (pt: number) => `${(pt * PT_TO_MM).toFixed(1)} mm`;
/** Keep at least this fraction of the page for content when dragging a guide. */
const MIN_GAP_FRAC = 0.06;

export function CompanyLetterheadSection({ companyId }: { companyId: string }) {
  const query = useApiQuery(['company-letterhead', companyId], (signal) => getLetterhead(companyId, signal));
  const data = query.data;

  return (
    <section className="space-y-3">
      <div>
        <h2 className="text-sm font-semibold text-muted-foreground">Letterhead</h2>
        <p className="text-xs text-muted-foreground">
          Upload the company letterhead (PDF{data?.wordConversionAvailable ? ' or Word' : ''}); its first page
          is printed as the background of every generated document — offer letters, agreements, offboarding
          documents and letters. Drag the guides to set where text may sit. It applies to documents generated{' '}
          <strong>from now on</strong>; existing PDFs are unchanged, and the onboarding forms are never branded.
        </p>
      </div>

      {query.isLoading ? (
        <LoadingSkeleton lines={4} />
      ) : query.isError || !data ? (
        <EmptyState
          icon={FileText}
          title="Couldn't load the letterhead"
          description={query.error?.message ?? 'Please try again.'}
        />
      ) : data.present ? (
        <Editor companyId={companyId} data={data} onChanged={() => query.refetch()} />
      ) : (
        <UploadCard companyId={companyId} data={data} onChanged={() => query.refetch()} />
      )}
    </section>
  );
}

/** The empty state: a single upload control (PDF, plus Word when the server can convert). */
function UploadCard({
  companyId,
  data,
  onChanged,
}: {
  companyId: string;
  data: Letterhead;
  onChanged: () => void;
}) {
  return (
    <div className="flex flex-col items-center justify-center gap-3 rounded-2xl border border-dashed bg-muted/20 p-8 text-center">
      <FileText className="size-8 text-muted-foreground" aria-hidden />
      <p className="text-sm text-muted-foreground">
        No letterhead yet — documents render plain. Upload a {data.wordConversionAvailable ? 'PDF or Word' : 'PDF'}{' '}
        file (≤10&nbsp;MB); the first page is used.
      </p>
      <UploadButton companyId={companyId} data={data} onChanged={onChanged} label="Upload letterhead" />
    </div>
  );
}

/** The shared upload button + hidden file input (accept adapts to the server's Word capability). */
function UploadButton({
  companyId,
  data,
  onChanged,
  label,
}: {
  companyId: string;
  data: Letterhead;
  onChanged: () => void;
  label: string;
}) {
  const [busy, setBusy] = React.useState(false);
  const inputRef = React.useRef<HTMLInputElement>(null);
  const accept = data.wordConversionAvailable
    ? `${LETTERHEAD_ACCEPT_PDF},${LETTERHEAD_ACCEPT_WORD}`
    : LETTERHEAD_ACCEPT_PDF;

  async function onPick(event: React.ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file) return;
    setBusy(true);
    try {
      await uploadLetterhead(companyId, file, data.wordConversionAvailable);
      toast.success('Letterhead uploaded — set the margins below, then it applies to new documents.');
      onChanged();
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Upload failed');
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <input ref={inputRef} type="file" accept={accept} className="hidden" onChange={onPick} />
      <Button type="button" variant="outline" size="sm" disabled={busy} onClick={() => inputRef.current?.click()}>
        <Upload className="size-4" />
        {busy ? 'Uploading…' : label}
      </Button>
    </>
  );
}

type Edge = 'top' | 'bottom' | 'left' | 'right';

/** The live margin editor: the page preview with four draggable guides + flowing sample text. */
function Editor({ companyId, data, onChanged }: { companyId: string; data: Letterhead; onChanged: () => void }) {
  const pageW = data.pageWidthPt ?? 595.276;
  const pageH = data.pageHeightPt ?? 841.89;
  const serverMargins = React.useMemo<LetterheadMargins>(
    () => ({
      topPt: data.marginTopPt ?? pageH * 0.25,
      bottomPt: data.marginBottomPt ?? pageH * 0.15,
      leftPt: data.marginLeftPt ?? 48,
      rightPt: data.marginRightPt ?? 48,
    }),
    [data, pageH],
  );

  const [margins, setMargins] = React.useState<LetterheadMargins>(serverMargins);
  const [busy, setBusy] = React.useState(false);
  React.useEffect(() => setMargins(serverMargins), [serverMargins]);

  const boxRef = React.useRef<HTMLDivElement>(null);
  const dragging = React.useRef<Edge | null>(null);

  const dirty =
    Math.round(margins.topPt) !== Math.round(serverMargins.topPt) ||
    Math.round(margins.bottomPt) !== Math.round(serverMargins.bottomPt) ||
    Math.round(margins.leftPt) !== Math.round(serverMargins.leftPt) ||
    Math.round(margins.rightPt) !== Math.round(serverMargins.rightPt);

  // Fractions of the page (0..1) for absolute positioning of the guides + the content box.
  const fTop = margins.topPt / pageH;
  const fBottom = margins.bottomPt / pageH;
  const fLeft = margins.leftPt / pageW;
  const fRight = margins.rightPt / pageW;

  const onPointerMove = React.useCallback(
    (e: PointerEvent) => {
      const edge = dragging.current;
      const box = boxRef.current;
      if (!edge || !box) return;
      const rect = box.getBoundingClientRect();
      setMargins((m) => {
        if (edge === 'top') {
          const frac = clamp((e.clientY - rect.top) / rect.height, 0, 1 - fracOf(m.bottomPt, pageH) - MIN_GAP_FRAC);
          return { ...m, topPt: frac * pageH };
        }
        if (edge === 'bottom') {
          const frac = clamp((rect.bottom - e.clientY) / rect.height, 0, 1 - fracOf(m.topPt, pageH) - MIN_GAP_FRAC);
          return { ...m, bottomPt: frac * pageH };
        }
        if (edge === 'left') {
          const frac = clamp((e.clientX - rect.left) / rect.width, 0, 1 - fracOf(m.rightPt, pageW) - MIN_GAP_FRAC);
          return { ...m, leftPt: frac * pageW };
        }
        const frac = clamp((rect.right - e.clientX) / rect.width, 0, 1 - fracOf(m.leftPt, pageW) - MIN_GAP_FRAC);
        return { ...m, rightPt: frac * pageW };
      });
    },
    [pageH, pageW],
  );

  const endDrag = React.useCallback(() => {
    dragging.current = null;
    window.removeEventListener('pointermove', onPointerMove);
    window.removeEventListener('pointerup', endDrag);
  }, [onPointerMove]);

  const startDrag = (edge: Edge) => (e: React.PointerEvent) => {
    e.preventDefault();
    dragging.current = edge;
    window.addEventListener('pointermove', onPointerMove);
    window.addEventListener('pointerup', endDrag);
  };

  React.useEffect(() => endDrag, [endDrag]);

  async function persist(next: LetterheadMargins) {
    setBusy(true);
    try {
      await saveLetterheadMargins(companyId, {
        topPt: Math.round(next.topPt),
        bottomPt: Math.round(next.bottomPt),
        leftPt: Math.round(next.leftPt),
        rightPt: Math.round(next.rightPt),
      });
      toast.success('Margins saved — they apply to documents generated from now on.');
      onChanged();
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Could not save the margins');
    } finally {
      setBusy(false);
    }
  }

  async function onReset() {
    setBusy(true);
    try {
      await resetLetterheadMargins(companyId);
      toast.success('Margins reset to the defaults.');
      onChanged();
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Could not reset the margins');
    } finally {
      setBusy(false);
    }
  }

  async function onRemove() {
    setBusy(true);
    try {
      await removeLetterhead(companyId);
      toast.success('Letterhead removed — documents render plain again.');
      onChanged();
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Could not remove the letterhead');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="space-y-4">
      <div className="grid gap-5 lg:grid-cols-[minmax(0,22rem)_1fr]">
        {/* The page preview with draggable guides + flowing sample text. */}
        <div className="mx-auto w-full max-w-sm">
          <div
            ref={boxRef}
            className="relative select-none overflow-hidden rounded-lg border bg-white shadow-card"
            style={{ aspectRatio: `${pageW} / ${pageH}`, touchAction: 'none' }}
          >
            {data.previewUrl ? (
              // eslint-disable-next-line @next/next/no-img-element -- presigned preview, not a static asset
              <img
                src={data.previewUrl}
                alt="Letterhead preview"
                className="pointer-events-none absolute inset-0 h-full w-full object-fill"
              />
            ) : null}

            {/* Content box (where real text lands) — flowing sample paragraphs, clipped to the margins. */}
            <div
              className="absolute overflow-hidden bg-primary/5 ring-1 ring-inset ring-primary/40"
              style={{
                top: `${fTop * 100}%`,
                bottom: `${fBottom * 100}%`,
                left: `${fLeft * 100}%`,
                right: `${fRight * 100}%`,
              }}
            >
              <p className="p-1 text-justify text-[6px] leading-tight text-foreground/80">{SAMPLE_TEXT}</p>
            </div>

            {/* Guides. Top/bottom are horizontal, left/right vertical — all draggable. */}
            <Guide axis="h" at={fTop} onPointerDown={startDrag('top')} />
            <Guide axis="h" at={1 - fBottom} onPointerDown={startDrag('bottom')} />
            <Guide axis="v" at={fLeft} onPointerDown={startDrag('left')} />
            <Guide axis="v" at={1 - fRight} onPointerDown={startDrag('right')} />
          </div>
          <p className="mt-1.5 text-center text-[11px] text-muted-foreground">
            Drag the guides to set the margins. Sample text shows where real content lands.
          </p>
        </div>

        {/* Readouts + actions. */}
        <div className="space-y-3">
          <dl className="grid grid-cols-2 gap-2 text-sm">
            <Readout label="Top" value={mmLabel(margins.topPt)} />
            <Readout label="Bottom" value={mmLabel(margins.bottomPt)} />
            <Readout label="Left" value={mmLabel(margins.leftPt)} />
            <Readout label="Right" value={mmLabel(margins.rightPt)} />
          </dl>
          <p className="text-xs text-muted-foreground">
            Page {Math.round(pageW * PT_TO_MM)}×{Math.round(pageH * PT_TO_MM)} mm
            {data.originalType && !data.originalType.includes('pdf') ? ' · converted from Word' : ''}.
          </p>
          <div className="flex flex-wrap gap-2">
            <Button type="button" size="sm" disabled={busy || !dirty} onClick={() => persist(margins)}>
              <Save className="size-4" />
              {busy ? 'Saving…' : 'Save margins'}
            </Button>
            <Button type="button" variant="outline" size="sm" disabled={busy} onClick={onReset}>
              <RotateCcw className="size-4" />
              Reset
            </Button>
            <UploadButton companyId={companyId} data={data} onChanged={onChanged} label="Replace" />
            <Button type="button" variant="outline" size="sm" disabled={busy} onClick={onRemove}>
              <Trash2 className="size-4" />
              Remove
            </Button>
          </div>
        </div>
      </div>
    </div>
  );
}

function Readout({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-center justify-between rounded-md border bg-card px-3 py-2">
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="font-medium tabular-nums">{value}</dd>
    </div>
  );
}

/** A single draggable guide line (a thin coloured line with a larger invisible hit area). */
function Guide({
  axis,
  at,
  onPointerDown,
}: {
  axis: 'h' | 'v';
  at: number;
  onPointerDown: (e: React.PointerEvent) => void;
}) {
  const horizontal = axis === 'h';
  return (
    <div
      role="separator"
      aria-orientation={horizontal ? 'horizontal' : 'vertical'}
      onPointerDown={onPointerDown}
      className={
        horizontal
          ? 'absolute inset-x-0 flex h-3 -translate-y-1/2 cursor-ns-resize items-center'
          : 'absolute inset-y-0 flex w-3 -translate-x-1/2 cursor-ew-resize justify-center'
      }
      style={horizontal ? { top: `${at * 100}%` } : { left: `${at * 100}%` }}
    >
      <div className={horizontal ? 'h-px w-full bg-primary' : 'h-full w-px bg-primary'} />
    </div>
  );
}

const SAMPLE_TEXT =
  'This is where the document content will appear. Every generated letter and agreement flows within these ' +
  'margins, on top of the letterhead artwork, and continues onto more pages if needed — each carrying the same ' +
  'letterhead. Pull the guides in until the sample text clears the logo, address and any footer on the page.';

function clamp(v: number, lo: number, hi: number): number {
  return Math.max(lo, Math.min(hi, v));
}

function fracOf(pt: number, pagePt: number): number {
  return pt / pagePt;
}
