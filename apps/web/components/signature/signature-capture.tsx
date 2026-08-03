'use client';

import * as React from 'react';
import { Eraser, PenLine, Sparkles, Upload, Wand2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { cn } from '@/lib/utils';
import { SIGNATURE_STYLES, type SignatureStyleId } from '@/lib/signature/styles';
import {
  SIGNATURE_WIDTH as W,
  SIGNATURE_HEIGHT as H,
  cleanToStandard,
  normalizeToStandard,
  fitFontPx,
  DEFAULT_INK,
  type RasterImage,
} from '@/lib/signature/image';

const ADOPT_TEXT =
  'By adopting it you agree it is the legal equivalent of your handwritten signature.';
const MAX_UPLOAD = 5 * 1024 * 1024; // 5MB
const ACCEPT = 'image/png,image/jpeg,image/webp';

type Mode = 'draw' | 'generate' | 'upload' | 'clean';

const MODES: { id: Mode; label: string; icon: React.ComponentType<{ className?: string }> }[] = [
  { id: 'draw', label: 'Draw', icon: PenLine },
  { id: 'generate', label: 'Generate', icon: Sparkles },
  { id: 'upload', label: 'Upload', icon: Upload },
  { id: 'clean', label: 'Upload & clean', icon: Wand2 },
];

/** Resolve a theme token to `hsl(...)` for the 2D canvas (theme-INVARIANT --signature-* — dark-on-white). */
function tokenColor(name: string, fallback: string): string {
  if (typeof window === 'undefined') return fallback;
  const v = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  return v ? `hsl(${v})` : fallback;
}
/** The invariant ink token as an [r,g,b] triple for the pure pixel functions. */
function inkRgb(): [number, number, number] {
  if (typeof document === 'undefined') return DEFAULT_INK;
  const cv = document.createElement('canvas');
  cv.width = cv.height = 1;
  const cx = cv.getContext('2d');
  if (!cx) return DEFAULT_INK;
  cx.fillStyle = tokenColor('--signature-ink', '#111827');
  cx.fillRect(0, 0, 1, 1);
  const [r, g, b] = cx.getImageData(0, 0, 1, 1).data;
  return [r, g, b];
}
function cssVar(name: string): string {
  return typeof document === 'undefined'
    ? ''
    : getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}
function paintPaper(cx: CanvasRenderingContext2D): void {
  cx.fillStyle = tokenColor('--signature-paper', '#ffffff');
  cx.fillRect(0, 0, W, H);
}
function toPng(canvas: HTMLCanvasElement): Promise<Blob | null> {
  return new Promise((res) => canvas.toBlob((b) => res(b), 'image/png'));
}
function putRaster(canvas: HTMLCanvasElement, r: RasterImage): void {
  canvas.width = r.width;
  canvas.height = r.height;
  const cx = canvas.getContext('2d');
  if (!cx) return;
  const img = cx.createImageData(r.width, r.height);
  img.data.set(r.data);
  cx.putImageData(img, 0, 0);
}
async function fileToRaster(file: File, maxDim = 1600): Promise<RasterImage> {
  const bitmap = await createImageBitmap(file);
  const scale = Math.min(1, maxDim / Math.max(bitmap.width, bitmap.height));
  const w = Math.max(1, Math.round(bitmap.width * scale));
  const h = Math.max(1, Math.round(bitmap.height * scale));
  const cv = document.createElement('canvas');
  cv.width = w;
  cv.height = h;
  const cx = cv.getContext('2d', { willReadFrequently: true })!;
  cx.fillStyle = '#ffffff';
  cx.fillRect(0, 0, w, h);
  cx.drawImage(bitmap, 0, 0, w, h);
  bitmap.close?.();
  return cx.getImageData(0, 0, w, h);
}

/**
 * The canonical signature capture (§3, "Signature capture standard") — four modes, ONE output contract:
 * whatever the mode, {@code onAdopt} receives a dark-ink-on-white PNG {@code Blob} at the standard
 * dimensions, indistinguishable downstream (storage, PDF stamp) from a drawn one. Every canvas paints on the
 * theme-INVARIANT --signature-paper/--signature-ink tokens so the PDF stamp holds in any app theme.
 */
export function SignatureCapture({
  fullName,
  onAdopt,
  disabled,
}: {
  fullName?: string;
  onAdopt: (blob: Blob) => void;
  disabled?: boolean;
}) {
  const [mode, setMode] = React.useState<Mode>('draw');

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap gap-2" role="tablist" aria-label="Signature method">
        {MODES.map((m) => {
          const Icon = m.icon;
          return (
            <Button
              key={m.id}
              type="button"
              size="sm"
              role="tab"
              aria-selected={mode === m.id}
              variant={mode === m.id ? 'default' : 'outline'}
              onClick={() => setMode(m.id)}
              disabled={disabled}
            >
              <Icon className="size-4" />
              {m.label}
            </Button>
          );
        })}
      </div>

      {mode === 'draw' ? <DrawMode onAdopt={onAdopt} disabled={disabled} /> : null}
      {mode === 'generate' ? (
        <GenerateMode fullName={fullName} onAdopt={onAdopt} disabled={disabled} />
      ) : null}
      {mode === 'upload' ? <UploadMode clean={false} onAdopt={onAdopt} disabled={disabled} /> : null}
      {mode === 'clean' ? <UploadMode clean onAdopt={onAdopt} disabled={disabled} /> : null}
    </div>
  );
}

/** Legal-language line + the explicit Adopt action, shared by every mode. */
function AdoptFooter({
  onAdopt,
  disabled,
  hint,
}: {
  onAdopt: () => void;
  disabled: boolean;
  hint?: string;
}) {
  return (
    <div className="space-y-2">
      {hint ? <p className="text-xs text-muted-foreground">{hint}</p> : null}
      <p className="text-xs text-muted-foreground">{ADOPT_TEXT}</p>
      <Button type="button" size="sm" onClick={onAdopt} disabled={disabled}>
        Adopt signature
      </Button>
    </div>
  );
}

const CANVAS_CLASS =
  'w-full touch-none rounded-md border border-input bg-[hsl(var(--signature-paper))]';

// --- Draw -----------------------------------------------------------------

function DrawMode({ onAdopt, disabled }: { onAdopt: (b: Blob) => void; disabled?: boolean }) {
  const ref = React.useRef<HTMLCanvasElement | null>(null);
  const drawing = React.useRef(false);
  const [dirty, setDirty] = React.useState(false);
  const ctx = () => ref.current?.getContext('2d') ?? null;

  React.useEffect(() => {
    const c = ctx();
    if (c) paintPaper(c);
  }, []);

  const point = (e: React.PointerEvent<HTMLCanvasElement>) => {
    const rect = ref.current!.getBoundingClientRect();
    return { x: ((e.clientX - rect.left) / rect.width) * W, y: ((e.clientY - rect.top) / rect.height) * H };
  };
  const start = (e: React.PointerEvent<HTMLCanvasElement>) => {
    if (disabled) return;
    drawing.current = true;
    const c = ctx();
    if (!c) return;
    c.strokeStyle = tokenColor('--signature-ink', '#111827');
    c.lineWidth = 2.2;
    c.lineCap = 'round';
    c.lineJoin = 'round';
    const { x, y } = point(e);
    c.beginPath();
    c.moveTo(x, y);
    ref.current!.setPointerCapture(e.pointerId);
  };
  const move = (e: React.PointerEvent<HTMLCanvasElement>) => {
    if (!drawing.current || disabled) return;
    const c = ctx();
    if (!c) return;
    const { x, y } = point(e);
    c.lineTo(x, y);
    c.stroke();
    if (!dirty) setDirty(true);
  };
  const end = () => {
    drawing.current = false;
  };
  const clear = () => {
    const c = ctx();
    if (c) paintPaper(c);
    setDirty(false);
  };
  const adopt = async () => {
    if (ref.current) {
      const b = await toPng(ref.current);
      if (b) onAdopt(b);
    }
  };

  return (
    <div className="space-y-3">
      <div className="flex justify-end">
        <Button type="button" size="sm" variant="ghost" onClick={clear} disabled={disabled}>
          <Eraser className="size-4" />
          Clear
        </Button>
      </div>
      <canvas
        ref={ref}
        width={W}
        height={H}
        onPointerDown={start}
        onPointerMove={move}
        onPointerUp={end}
        onPointerLeave={end}
        aria-label="Signature drawing pad"
        className={cn(CANVAS_CLASS, disabled ? 'cursor-default' : 'cursor-crosshair')}
      />
      <AdoptFooter onAdopt={adopt} disabled={Boolean(disabled) || !dirty} hint="Sign above with your mouse or finger." />
    </div>
  );
}

// --- Generate -------------------------------------------------------------

function GenerateMode({
  fullName,
  onAdopt,
  disabled,
}: {
  fullName?: string;
  onAdopt: (b: Blob) => void;
  disabled?: boolean;
}) {
  const [name, setName] = React.useState(fullName?.trim() ?? '');
  const [styleId, setStyleId] = React.useState<SignatureStyleId>(SIGNATURE_STYLES[0].id);

  const rasterize = async (): Promise<Blob | null> => {
    const style = SIGNATURE_STYLES.find((s) => s.id === styleId) ?? SIGNATURE_STYLES[0];
    const family = cssVar(style.cssVar) || 'cursive';
    const cv = document.createElement('canvas');
    cv.width = W;
    cv.height = H;
    const cx = cv.getContext('2d')!;
    paintPaper(cx);
    const maxPx = 88;
    try {
      await document.fonts.load(`${maxPx}px ${family}`, name || 'Aa');
    } catch {
      /* fall back to whatever is loaded */
    }
    cx.fillStyle = tokenColor('--signature-ink', '#111827');
    cx.textBaseline = 'middle';
    cx.textAlign = 'center';
    const measure = (px: number) => {
      cx.font = `${px}px ${family}`;
      return cx.measureText(name).width;
    };
    const px = fitFontPx(measure, W - 44, maxPx, 22);
    cx.font = `${px}px ${family}`;
    cx.fillText(name, W / 2, H / 2 + 6);
    return toPng(cv);
  };

  const adopt = async () => {
    const b = await rasterize();
    if (b) onAdopt(b);
  };

  return (
    <div className="space-y-3">
      <Input
        value={name}
        onChange={(e) => setName(e.target.value)}
        placeholder="Type your full name"
        aria-label="Name to render as a signature"
        disabled={disabled}
      />
      <div className="grid grid-cols-1 gap-2 sm:grid-cols-3">
        {SIGNATURE_STYLES.map((s) => (
          <button
            key={s.id}
            type="button"
            onClick={() => setStyleId(s.id)}
            aria-pressed={styleId === s.id}
            disabled={disabled}
            className={cn(
              'flex h-[70px] flex-col items-center justify-center gap-1 rounded-md border bg-[hsl(var(--signature-paper))] px-2 transition-colors',
              styleId === s.id ? 'border-primary ring-2 ring-primary/40' : 'border-input hover:border-primary/50',
            )}
          >
            <span
              className="max-w-full truncate text-2xl text-[hsl(var(--signature-ink))]"
              style={{ fontFamily: `var(${s.cssVar})` }}
            >
              {name || 'Your name'}
            </span>
            <span className="text-[10px] uppercase tracking-wide text-muted-foreground">{s.label}</span>
          </button>
        ))}
      </div>
      <AdoptFooter
        onAdopt={adopt}
        disabled={Boolean(disabled) || name.trim().length === 0}
        hint="Pick a style; long names scale to fit the signature box."
      />
    </div>
  );
}

// --- Upload / Upload & clean ---------------------------------------------

function UploadMode({
  clean,
  onAdopt,
  disabled,
}: {
  clean: boolean;
  onAdopt: (b: Blob) => void;
  disabled?: boolean;
}) {
  const previewRef = React.useRef<HTMLCanvasElement | null>(null);
  const sourceRef = React.useRef<RasterImage | null>(null);
  const [ready, setReady] = React.useState(false);
  const [error, setError] = React.useState<string | null>(null);
  const [sensitivity, setSensitivity] = React.useState(0.5);

  const render = React.useCallback(
    (src: RasterImage, s: number) => {
      const out = clean ? cleanToStandard(src, s, inkRgb()) : normalizeToStandard(src);
      if (previewRef.current) putRaster(previewRef.current, out);
      setReady(true);
    },
    [clean],
  );

  const onFile = async (file: File | undefined) => {
    setError(null);
    if (!file) return;
    if (!ACCEPT.split(',').includes(file.type)) {
      setError('Please choose a PNG, JPG or WebP image.');
      return;
    }
    if (file.size > MAX_UPLOAD) {
      setError('That image is over 5MB — please choose a smaller one.');
      return;
    }
    try {
      const src = await fileToRaster(file);
      sourceRef.current = src;
      render(src, sensitivity);
    } catch {
      setError("Couldn't read that image. Try a different file.");
    }
  };

  const onSensitivity = (s: number) => {
    setSensitivity(s);
    if (sourceRef.current) render(sourceRef.current, s);
  };

  const reset = () => {
    sourceRef.current = null;
    setReady(false);
    setError(null);
    const c = previewRef.current?.getContext('2d');
    if (c && previewRef.current) {
      previewRef.current.width = W;
      previewRef.current.height = H;
      paintPaper(c);
    }
  };

  const adopt = async () => {
    if (previewRef.current && ready) {
      const b = await toPng(previewRef.current);
      if (b) onAdopt(b);
    }
  };

  React.useEffect(() => {
    reset();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [clean]);

  return (
    <div className="space-y-3">
      <label className="flex flex-col gap-1.5">
        <span className="text-sm font-medium">Signature image</span>
        <input
          type="file"
          accept={ACCEPT}
          disabled={disabled}
          onChange={(e) => onFile(e.target.files?.[0])}
          className="block w-full text-sm text-muted-foreground file:mr-3 file:rounded-md file:border-0 file:bg-primary file:px-3 file:py-2 file:text-sm file:font-medium file:text-primary-foreground hover:file:bg-primary/90"
        />
      </label>
      {error ? <p className="text-xs text-destructive">{error}</p> : null}

      <canvas
        ref={previewRef}
        width={W}
        height={H}
        aria-label={clean ? 'Cleaned signature preview' : 'Signature preview'}
        className={CANVAS_CLASS}
      />

      {clean && ready ? (
        <label className="flex items-center gap-3 text-xs text-muted-foreground">
          <span className="shrink-0">Sensitivity</span>
          <input
            type="range"
            min={0}
            max={100}
            value={Math.round(sensitivity * 100)}
            onChange={(e) => onSensitivity(Number(e.target.value) / 100)}
            disabled={disabled}
            className="w-full"
            aria-label="Cleaning sensitivity"
          />
        </label>
      ) : null}

      <div className="flex gap-2">
        {ready ? (
          <Button type="button" size="sm" variant="ghost" onClick={reset} disabled={disabled}>
            Try again
          </Button>
        ) : null}
      </div>

      <AdoptFooter
        onAdopt={adopt}
        disabled={Boolean(disabled) || !ready}
        hint={
          clean
            ? 'Cleans best from plain paper with clear, dark ink. Adjust sensitivity if strokes drop out or specks remain.'
            : 'A plain signature image is fitted onto white. Use a clean scan/photo of just your signature.'
        }
      />
    </div>
  );
}
