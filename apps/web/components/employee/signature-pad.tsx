'use client';

import * as React from 'react';
import { Eraser, PenLine, Type } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { cn } from '@/lib/utils';

const WIDTH = 520;
const HEIGHT = 170;

type Mode = 'draw' | 'type';

/**
 * Resolve a theme token to an `hsl(...)` string for the 2D canvas. The signature uses the theme-INVARIANT
 * `--signature-paper` / `--signature-ink` tokens (identical in light + dark) so the captured PNG is always
 * dark-on-white for the PDF stamp, while removing the raw hex literals.
 */
function tokenColor(name: string, fallback: string): string {
  if (typeof window === 'undefined') return fallback;
  const value = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  return value ? `hsl(${value})` : fallback;
}

/**
 * Capture a single e-signature — drawn on a canvas or typed and rendered in a script face. Emits a
 * PNG data URL (and how it was captured) whenever it changes, or null when cleared.
 */
export function SignaturePad({
  onChange,
  disabled,
}: {
  onChange: (dataUrl: string | null, type: 'DRAWN' | 'TYPED') => void;
  disabled?: boolean;
}) {
  const canvasRef = React.useRef<HTMLCanvasElement | null>(null);
  const drawing = React.useRef(false);
  const dirty = React.useRef(false);
  const [mode, setMode] = React.useState<Mode>('draw');
  const [typed, setTyped] = React.useState('');

  const ctx = () => canvasRef.current?.getContext('2d') ?? null;

  const paintBackground = React.useCallback(() => {
    const c = ctx();
    if (!c) return;
    c.fillStyle = tokenColor('--signature-paper', '#ffffff');
    c.fillRect(0, 0, WIDTH, HEIGHT);
  }, []);

  React.useEffect(() => {
    paintBackground();
  }, [paintBackground]);

  const point = (e: React.PointerEvent<HTMLCanvasElement>) => {
    const rect = canvasRef.current!.getBoundingClientRect();
    return {
      x: ((e.clientX - rect.left) / rect.width) * WIDTH,
      y: ((e.clientY - rect.top) / rect.height) * HEIGHT,
    };
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
    canvasRef.current!.setPointerCapture(e.pointerId);
  };

  const move = (e: React.PointerEvent<HTMLCanvasElement>) => {
    if (!drawing.current || disabled) return;
    const c = ctx();
    if (!c) return;
    const { x, y } = point(e);
    c.lineTo(x, y);
    c.stroke();
    dirty.current = true;
  };

  const end = () => {
    if (!drawing.current) return;
    drawing.current = false;
    if (dirty.current) {
      onChange(canvasRef.current!.toDataURL('image/png'), 'DRAWN');
    }
  };

  const clear = () => {
    paintBackground();
    dirty.current = false;
    setTyped('');
    onChange(null, mode === 'draw' ? 'DRAWN' : 'TYPED');
  };

  const renderTyped = (value: string) => {
    setTyped(value);
    const c = ctx();
    if (!c) return;
    paintBackground();
    if (!value.trim()) {
      onChange(null, 'TYPED');
      return;
    }
    c.fillStyle = tokenColor('--signature-ink', '#111827');
    c.font = "48px 'Segoe Script','Brush Script MT',cursive";
    c.textBaseline = 'middle';
    c.fillText(value, 24, HEIGHT / 2);
    onChange(canvasRef.current!.toDataURL('image/png'), 'TYPED');
  };

  const switchMode = (next: Mode) => {
    setMode(next);
    clear();
  };

  return (
    <div className="space-y-3">
      <div className="flex gap-2">
        <Button
          type="button"
          size="sm"
          variant={mode === 'draw' ? 'default' : 'outline'}
          onClick={() => switchMode('draw')}
          disabled={disabled}
        >
          <PenLine className="size-4" />
          Draw
        </Button>
        <Button
          type="button"
          size="sm"
          variant={mode === 'type' ? 'default' : 'outline'}
          onClick={() => switchMode('type')}
          disabled={disabled}
        >
          <Type className="size-4" />
          Type
        </Button>
        <Button type="button" size="sm" variant="ghost" className="ml-auto" onClick={clear} disabled={disabled}>
          <Eraser className="size-4" />
          Clear
        </Button>
      </div>

      {mode === 'type' ? (
        <Input
          value={typed}
          onChange={(e) => renderTyped(e.target.value)}
          placeholder="Type your full name"
          disabled={disabled}
          aria-label="Typed Signature"
        />
      ) : null}

      <canvas
        ref={canvasRef}
        width={WIDTH}
        height={HEIGHT}
        onPointerDown={start}
        onPointerMove={move}
        onPointerUp={end}
        onPointerLeave={end}
        className={cn(
          'w-full touch-none rounded-md border border-input bg-[hsl(var(--signature-paper))]',
          mode === 'draw' && !disabled ? 'cursor-crosshair' : 'cursor-default',
        )}
      />
      <p className="text-xs text-muted-foreground">
        {mode === 'draw'
          ? 'Sign above using your mouse or finger.'
          : 'Your typed name is rendered as your signature.'}
      </p>
    </div>
  );
}
