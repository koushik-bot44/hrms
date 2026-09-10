'use client';

import * as React from 'react';
import { CreditCard, Fingerprint, Hand, KeyRound, ScanFace, ShieldQuestion } from 'lucide-react';
import { cn } from '@/lib/utils';

/**
 * WHICH CREDENTIAL got somebody through the door.
 *
 * <p>Worth a badge because "they badged" and "their face was recognised" are different facts, and
 * only one of them corresponds to a template this system can push to another terminal. On this
 * fleet 98.5% of passes are Face and 1.1% are Finger — so a person showing Finger everywhere is the
 * unusual one, and a Card pass is worth noticing at a glance.
 */
const ICONS: Record<string, React.ComponentType<{ className?: string }>> = {
  FACE: ScanFace,
  FINGER: Fingerprint,
  CARD: CreditCard,
  PALM: Hand,
  PASSWORD: KeyRound,
  OTHER: ShieldQuestion,
};

/** Face is the norm here, so it stays quiet; anything else is what an operator wants to spot. */
const TONE: Record<string, string> = {
  FACE: 'text-muted-foreground',
  FINGER: 'text-primary',
  CARD: 'text-warning',
  PALM: 'text-warning',
  PASSWORD: 'text-warning',
  OTHER: 'text-muted-foreground',
};

export function VerifyModeBadge({
  mode,
  label,
  withLabel = false,
  className,
}: {
  mode: string;
  label?: string;
  withLabel?: boolean;
  className?: string;
}) {
  const Icon = ICONS[mode] ?? ICONS.OTHER;
  const text = label ?? mode;
  return (
    <span
      className={cn('inline-flex items-center gap-1 text-xs', TONE[mode] ?? TONE.OTHER, className)}
      title={`Passed by ${text}`}
    >
      <Icon className="size-3.5" aria-hidden />
      {withLabel ? text : <span className="sr-only">{text}</span>}
    </span>
  );
}
