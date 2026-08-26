'use client';

import { Badge } from '@/components/ui/badge';
import { Coffee, DoorClosed, DoorOpen, Utensils } from 'lucide-react';

/**
 * The four device roles, in one place.
 *
 * The pipeline stores {@code area × direction}, which is the right shape for the code and the wrong
 * shape for a person claiming a terminal: nobody standing next to a reader thinks "GATE, MIXED". They
 * think "this is the door people come in through". So the console offers four named roles and this
 * module is the only translation between the two vocabularies — the feed badge, the Devices list and
 * the claim picker all read from here, so they cannot drift into describing the same terminal
 * differently.
 *
 * MIXED stays API-only, deliberately. It exists for a reader that cannot tell entry from exit, which
 * makes every punch from it ambiguous; offering it in a picker would invite someone to choose it as a
 * shrug, and burst collapse cannot infer direction from a device that refuses to state one.
 */

export type DeviceRole = 'GATE_IN' | 'GATE_OUT' | 'CAFETERIA_IN' | 'CAFETERIA_OUT';

export interface RoleMeta {
  value: DeviceRole;
  label: string;
  /** What the terminal is FOR, in the operator's words — shown under the label in the picker. */
  hint: string;
  area: 'GATE' | 'CAFETERIA';
  direction: 'IN' | 'OUT';
  icon: React.ComponentType<{ className?: string }>;
}

export const DEVICE_ROLES: RoleMeta[] = [
  {
    value: 'GATE_IN',
    label: 'Gate — IN',
    hint: 'People tap here on the way into the building',
    area: 'GATE',
    direction: 'IN',
    icon: DoorOpen,
  },
  {
    value: 'GATE_OUT',
    label: 'Gate — OUT',
    hint: 'People tap here on the way out of the building',
    area: 'GATE',
    direction: 'OUT',
    icon: DoorClosed,
  },
  {
    value: 'CAFETERIA_IN',
    label: 'Cafeteria — IN',
    hint: 'Tapping here STARTS a break — they have left their desk',
    area: 'CAFETERIA',
    direction: 'IN',
    icon: Coffee,
  },
  {
    value: 'CAFETERIA_OUT',
    label: 'Cafeteria — OUT',
    hint: 'Tapping here ENDS a break — they are back at their desk',
    area: 'CAFETERIA',
    direction: 'OUT',
    icon: Utensils,
  },
];

/** area × direction → the named role, or null for MIXED and for an unclaimed terminal. */
export function roleOf(area: string | null, direction: string | null): RoleMeta | null {
  return (
    DEVICE_ROLES.find((r) => r.area === area && r.direction === direction) ?? null
  );
}

export function roleByValue(value: DeviceRole): RoleMeta {
  const found = DEVICE_ROLES.find((r) => r.value === value);
  if (!found) throw new Error(`unknown device role: ${value}`);
  return found;
}

/**
 * The badge every surface uses for a terminal's role.
 *
 * An unclaimed terminal reads "Unclaimed" rather than showing a blank — its punches are being captured
 * and attributed to nobody, which is a state worth naming. MIXED reads as itself, because a terminal
 * genuinely configured that way should look unusual.
 */
export function DeviceRoleBadge({
  area,
  direction,
  status,
}: {
  area: string | null;
  direction: string | null;
  status?: string;
}) {
  if (status && status !== 'CLAIMED') {
    return <Badge variant="neutral">Unclaimed</Badge>;
  }
  if (direction === 'MIXED') {
    return (
      <Badge variant="warning" title="This terminal does not distinguish entry from exit.">
        {area === 'CAFETERIA' ? 'Cafeteria' : 'Gate'} — MIXED
      </Badge>
    );
  }
  const role = roleOf(area, direction);
  if (!role) return <Badge variant="neutral">No role</Badge>;
  return (
    <Badge variant={role.area === 'CAFETERIA' ? 'primarySoft' : 'outline'} title={role.hint}>
      <role.icon className="size-3" aria-hidden />
      {role.label}
    </Badge>
  );
}
