'use client';

import { Badge } from '@/components/ui/badge';
import type { Presence } from '@/lib/api/iclock';

/**
 * Presence and health tones, kept as local lookup tables.
 *
 * Deliberately NOT folded into `components/status-badge.tsx`: that one maps IHRMS employee lifecycle
 * states (INVITED, ACTIVE, OFFBOARDED…), and presence is a different axis entirely — a person can be
 * OFFBOARDED in IHRMS and still be standing in the cafeteria. Sharing one map would force the two
 * vocabularies together and make every future addition ambiguous.
 */
const PRESENCE_META: Record<
  Presence,
  { label: string; variant: 'success' | 'warning' | 'neutral' | 'primarySoft' }
> = {
  IN_OFFICE: { label: 'In office', variant: 'success' },
  IN_CAFETERIA: { label: 'Cafeteria', variant: 'primarySoft' },
  LEFT: { label: 'Left', variant: 'neutral' },
  NOT_ARRIVED: { label: 'Not arrived', variant: 'warning' },
};

export function PresenceBadge({ presence }: { presence: Presence }) {
  const meta = PRESENCE_META[presence] ?? { label: presence, variant: 'neutral' as const };
  return <Badge variant={meta.variant}>{meta.label}</Badge>;
}

export function presenceLabel(presence: Presence): string {
  return PRESENCE_META[presence]?.label ?? presence;
}

/**
 * Why a pin resolves to nobody. The copy states the REMEDY, not the enum — an operator reading
 * "UNKNOWN_PIN" has to translate it before they can act, and INACTIVE_PERSON in particular has a
 * remedy (flip them active) that is the opposite of the obvious one (create a person).
 */
const REASON_META: Record<
  string,
  { label: string; hint: string; variant: 'warning' | 'danger' | 'neutral' | 'primarySoft' }
> = {
  UNKNOWN_PIN: {
    label: 'No identity',
    hint: 'This pin is not on the roster. Add the person, or import them.',
    variant: 'warning',
  },
  INACTIVE_PERSON: {
    label: 'Marked inactive',
    hint: 'This pin belongs to someone already on the roster who is switched off. Reactivate them — do not add a second person.',
    variant: 'primarySoft',
  },
  DEVICE_UNCLAIMED: {
    label: 'Device unclaimed',
    hint: 'The terminal that sent this is not adopted yet. Claim it on Devices.',
    variant: 'danger',
  },
  ANOMALY_OFFBOARDED: {
    label: 'Anomaly',
    hint: 'The pin resolves to an active person, so promotion should have worked. Run a sweep.',
    variant: 'danger',
  },
  NO_PIN: {
    label: 'Unreadable pin',
    hint: 'The terminal sent no usable pin on these punches.',
    variant: 'neutral',
  },
  UNPARSEABLE_TIME: {
    label: 'Unreadable time',
    hint: 'The terminal sent a timestamp that could not be parsed.',
    variant: 'neutral',
  },
};

export function reasonMeta(reason: string) {
  return (
    REASON_META[reason] ?? {
      label: reason,
      hint: 'Unrecognised reason — check the API logs.',
      variant: 'neutral' as const,
    }
  );
}

export function ReasonBadge({ reason }: { reason: string }) {
  const meta = reasonMeta(reason);
  return (
    <Badge variant={meta.variant} title={meta.hint}>
      {meta.label}
    </Badge>
  );
}
