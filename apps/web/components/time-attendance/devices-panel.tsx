'use client';

import * as React from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Router, ShieldCheck, ShieldOff } from 'lucide-react';
import {
  claimDevice,
  iclockKeys,
  listDevices,
  unclaimDevice,
  type IclockDevice,
  type IclockSite,
} from '@/lib/api/iclock';
import { useApiMutation, useApiQuery } from '@/lib/api/hooks';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Card } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { EmptyState } from '@/components/empty-state';
import { LoadingSkeleton } from '@/components/loading-skeleton';
import { surface } from '@/components/ui/surface';
import { istDateTime, relativeTime } from '@/lib/date';
import { cn } from '@/lib/utils';
import { DeviceHealthBadge } from './device-health-badge';
import { ConsoleError } from './console-error';

const SELECT_CLASS =
  'h-11 w-full rounded-md border border-input bg-background px-3 text-sm ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2';

/**
 * Claiming a terminal — the moment punches start being attributed.
 *
 * Area and direction are asked for together because the pipeline needs both: direction decides
 * whether a punch is an arrival or a departure, and area decides whether a burst chain breaks.
 *
 * ON A CAFETERIA READER, IN AND OUT INVERT: "IN for cafeteria is OUT for work". Tapping IN at the
 * cafeteria STARTS a break (presence becomes IN_CAFETERIA); tapping OUT ENDS it and puts the person
 * back at their desk (IN_OFFICE). Either way they are still in the building, which is only derivable
 * if the terminal declared itself as CAFETERIA at claim time.
 *
 * This comment previously said the opposite — that a cafeteria OUT meant "went for lunch". The code
 * in IclockBoardService was right and the comment was wrong, which is the worse way round: a reviewer
 * reading it concluded the presence logic was inverted when it was not.
 */
function ClaimDialog({
  device,
  siteId,
  siteName,
  open,
  onOpenChange,
}: {
  device: IclockDevice | null;
  siteId: string;
  siteName: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const qc = useQueryClient();
  const [area, setArea] = React.useState('GATE');
  const [direction, setDirection] = React.useState('IN');
  const [name, setName] = React.useState('');

  React.useEffect(() => {
    if (device) {
      setArea(device.area ?? 'GATE');
      setDirection(device.direction ?? 'IN');
      setName(device.name ?? '');
    }
  }, [device]);

  const claim = useApiMutation(
    () =>
      claimDevice(device?.id as string, {
        siteId,
        area,
        direction,
        name: name.trim() || undefined,
      }),
    {
      successMessage: 'Terminal claimed. Its punches will be attributed from now on.',
      onSuccess: () => {
        qc.invalidateQueries({ queryKey: iclockKeys.root });
        onOpenChange(false);
      },
    },
  );

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>Claim {device?.serialNumber}</DialogTitle>
          <DialogDescription>
            Adopts this terminal into {siteName}. Punches it has already sent are kept — claiming does
            not backfill them, but re-resolving from the People screen will.
          </DialogDescription>
        </DialogHeader>

        <form
          className="space-y-4"
          onSubmit={(e) => {
            e.preventDefault();
            claim.mutate();
          }}
        >
          <div className="space-y-1.5">
            <label className="text-sm font-medium" htmlFor="device-name">
              Name
            </label>
            <Input
              id="device-name"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="Main gate — entry"
            />
          </div>

          <div className="grid gap-4 sm:grid-cols-2">
            <div className="space-y-1.5">
              <label className="text-sm font-medium" htmlFor="device-area">
                Area
              </label>
              <select
                id="device-area"
                className={SELECT_CLASS}
                value={area}
                onChange={(e) => setArea(e.target.value)}
              >
                <option value="GATE">Gate</option>
                <option value="CAFETERIA">Cafeteria</option>
              </select>
            </div>
            <div className="space-y-1.5">
              <label className="text-sm font-medium" htmlFor="device-direction">
                Direction
              </label>
              <select
                id="device-direction"
                className={SELECT_CLASS}
                value={direction}
                onChange={(e) => setDirection(e.target.value)}
              >
                <option value="IN">In</option>
                <option value="OUT">Out</option>
                <option value="MIXED">Mixed</option>
              </select>
            </div>
          </div>

          <p className="text-xs text-muted-foreground">
            A cafeteria terminal marks people as away from their desk but still on site; a gate
            terminal decides arrival and departure.
          </p>

          <div className="flex justify-end gap-2">
            <Button type="button" variant="ghost" onClick={() => onOpenChange(false)}>
              Cancel
            </Button>
            <Button type="submit" disabled={claim.isPending}>
              <ShieldCheck className="size-4" />
              {claim.isPending ? 'Claiming…' : 'Claim'}
            </Button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}

function DeviceRow({ device, onClaim }: { device: IclockDevice; onClaim: () => void }) {
  const qc = useQueryClient();
  const unclaim = useApiMutation(() => unclaimDevice(device.id), {
    successMessage: 'Unclaimed. Punches are still captured, but no longer attributed.',
    onSuccess: () => qc.invalidateQueries({ queryKey: iclockKeys.root }),
  });

  return (
    <li className={cn(surface('subtle'), 'flex flex-wrap items-center justify-between gap-3 p-4')}>
      <div className="min-w-0">
        <div className="flex flex-wrap items-center gap-2">
          <span className="truncate font-medium">{device.name ?? device.serialNumber}</span>
          <DeviceHealthBadge
            healthy={device.status === 'CLAIMED' && isRecent(device.lastSeenAt)}
            minutesSinceSeen={minutesSince(device.lastSeenAt)}
            lastSeenAt={device.lastSeenAt}
            status={device.status}
          />
          {device.area ? <Badge variant="outline">{titleCase(device.area)}</Badge> : null}
          {device.direction ? <Badge variant="outline">{titleCase(device.direction)}</Badge> : null}
        </div>
        <div className="mt-1 font-mono text-xs text-muted-foreground">{device.serialNumber}</div>
        <div className="mt-1 text-xs text-muted-foreground">
          {device.rawPunchCount.toLocaleString()} punches captured
          {device.siteName ? ` · ${device.siteName}` : ''}
          {device.lastSeenAt ? (
            <span title={istDateTime(device.lastSeenAt)}>
              {' '}
              · last contact {relativeTime(device.lastSeenAt)}
            </span>
          ) : null}
        </div>
        {device.firmwareInfo ? (
          <div className="mt-1 truncate font-mono text-[11px] text-muted-foreground/70">
            {device.firmwareInfo}
          </div>
        ) : null}
      </div>

      <div className="shrink-0">
        {device.status === 'CLAIMED' ? (
          <Button
            variant="ghost"
            size="sm"
            onClick={() => unclaim.mutate()}
            disabled={unclaim.isPending}
          >
            <ShieldOff className="size-4" />
            Unclaim
          </Button>
        ) : (
          <Button variant="outline" size="sm" onClick={onClaim}>
            <ShieldCheck className="size-4" />
            Claim
          </Button>
        )}
      </div>
    </li>
  );
}

function minutesSince(iso: string | null): number {
  if (!iso) return Number.MAX_SAFE_INTEGER;
  return Math.floor((Date.now() - new Date(iso).getTime()) / 60_000);
}

/**
 * Mirrors `IclockBoardService.HEALTHY_MINUTES` — three poll intervals of silence and the terminal is
 * considered down.
 *
 * The Overview gets `healthy` computed server-side; this list does not (the devices endpoint predates
 * it), so the rule is restated here. It has to be the SAME number: two screens disagreeing about
 * whether a terminal is alive is worse than either answer on its own, because the operator then has
 * no way to tell which one to believe.
 */
const HEALTHY_MINUTES = 3;

function isRecent(iso: string | null): boolean {
  return minutesSince(iso) <= HEALTHY_MINUTES;
}

function titleCase(s: string): string {
  return s.charAt(0) + s.slice(1).toLowerCase();
}

export function DevicesPanel({ site }: { site: IclockSite | null }) {
  const [claiming, setClaiming] = React.useState<IclockDevice | null>(null);
  const query = useApiQuery(iclockKeys.devices(), (signal) => listDevices(undefined, signal), {
    retry: false,
  });

  if (query.isLoading) return <LoadingSkeleton lines={6} />;
  if (query.isError || !query.data) return <ConsoleError error={query.error} />;

  const devices = query.data;
  const unclaimed = devices.filter((d) => d.status !== 'CLAIMED');
  const claimed = devices.filter((d) => d.status === 'CLAIMED');

  if (devices.length === 0) {
    return (
      <EmptyState
        icon={Router}
        title="No terminals have called in"
        description="A terminal appears here the moment it first contacts the server — no registration step. Point one at the ADMS endpoint and reboot it."
      />
    );
  }

  return (
    <div className="space-y-6">
      {unclaimed.length > 0 ? (
        <Card className="p-6">
          <div className="mb-4">
            <h2 className="text-base font-semibold">Waiting to be adopted</h2>
            <p className="text-sm text-muted-foreground">
              These terminals are talking to the server and their punches are being captured, but
              nothing is attributed until they are claimed to a site.
            </p>
          </div>
          <ul className="space-y-3">
            {unclaimed.map((d) => (
              <DeviceRow key={d.id} device={d} onClaim={() => setClaiming(d)} />
            ))}
          </ul>
        </Card>
      ) : null}

      <Card className="p-6">
        <h2 className="mb-4 text-base font-semibold">Claimed terminals</h2>
        {claimed.length === 0 ? (
          <EmptyState
            icon={Router}
            title="Nothing claimed yet"
            description="Claim a terminal above to start attributing its punches."
            className="py-10"
          />
        ) : (
          <ul className="space-y-3">
            {claimed.map((d) => (
              <DeviceRow key={d.id} device={d} onClaim={() => setClaiming(d)} />
            ))}
          </ul>
        )}
      </Card>

      <ClaimDialog
        device={claiming}
        siteId={site?.id ?? ''}
        siteName={site?.name ?? 'this site'}
        open={Boolean(claiming)}
        onOpenChange={(open) => {
          if (!open) setClaiming(null);
        }}
      />
    </div>
  );
}
