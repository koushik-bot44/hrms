'use client';

import * as React from 'react';
import { useQueryClient } from '@tanstack/react-query';
import {
  Building2,
  MoveRight,
  Pencil,
  Router,
  ScrollText,
  ShieldCheck,
  ShieldOff,
  Tag,
} from 'lucide-react';
import {
  changeDeviceRole,
  claimDevice,
  createSite,
  iclockKeys,
  listDevices,
  listSites,
  moveDeviceBuilding,
  renameSite,
  unclaimDevice,
  type IclockDevice,
  type IclockSite,
} from '@/lib/api/iclock';
import { useApiMutation, useApiQuery } from '@/lib/api/hooks';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Card } from '@/components/ui/card';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { EmptyState } from '@/components/empty-state';
import { LoadingSkeleton } from '@/components/loading-skeleton';
import { GroupedList } from '@/components/console/grouped-list';
import { Combobox } from '@/components/console/combobox';
import { RowMenu } from '@/components/console/row-menu';
import {
  CommandChannelBadge,
  DeviceCommandLog,
  QueryUsersButton,
  SyncTimeButton,
} from './device-commands';
import { ViewToggle, usePersistedViewMode } from '@/components/console/view-toggle';
import { istDateTime, relativeTime } from '@/lib/date';
import { cn } from '@/lib/utils';
import { DEVICE_ROLES, DeviceRoleBadge, roleByValue, roleOf, type DeviceRole } from './device-role';
import { DeviceHealthBadge } from './device-health-badge';
import { ConsoleError } from './console-error';

/** Mirrors IclockBoardService.HEALTHY_MINUTES — two screens must not disagree about "alive". */
const HEALTHY_MINUTES = 3;

function minutesSince(iso: string | null): number {
  if (!iso) return Number.MAX_SAFE_INTEGER;
  return Math.floor((Date.now() - new Date(iso).getTime()) / 60_000);
}

/**
 * Terminals, grouped by building.
 *
 * Unclaimed terminals get their own section at the top rather than a group in the list: they belong to
 * no building by definition, and they are the only ones needing an action right now — their punches
 * are being captured and attributed to nobody.
 */
export function DevicesPanel({ site }: { site: IclockSite | null }) {
  const qc = useQueryClient();
  const [claiming, setClaiming] = React.useState<IclockDevice | null>(null);
  const [changingRole, setChangingRole] = React.useState<IclockDevice | null>(null);
  const [movingBuilding, setMovingBuilding] = React.useState<IclockDevice | null>(null);
  const [renaming, setRenaming] = React.useState<IclockSite | null>(null);
  const [creatingBuilding, setCreatingBuilding] = React.useState(false);
  const [mode, setMode] = usePersistedViewMode('devices');

  const devicesQuery = useApiQuery(iclockKeys.devices(), (s) => listDevices(undefined, s), {
    retry: false,
  });
  const sitesQuery = useApiQuery(iclockKeys.sites(), listSites, { retry: false });

  if (devicesQuery.isLoading) return <LoadingSkeleton lines={6} />;
  if (devicesQuery.isError || !devicesQuery.data) return <ConsoleError error={devicesQuery.error} />;

  const devices = devicesQuery.data;
  const buildings = sitesQuery.data ?? [];
  const unclaimed = devices.filter((d) => d.status !== 'CLAIMED');
  const claimed = devices.filter((d) => d.status === 'CLAIMED');

  if (devices.length === 0) {
    return (
      <>
        <BuildingsBar
          buildings={buildings}
          onRename={setRenaming}
          onCreate={() => setCreatingBuilding(true)}
        />
        <EmptyState
          icon={Router}
          title="No terminals have called in"
          description="A terminal appears here the moment it first contacts the server — no registration step. Point one at the ADMS endpoint and reboot it."
        />
        <BuildingDialogs
          renaming={renaming}
          setRenaming={setRenaming}
          creating={creatingBuilding}
          setCreating={setCreatingBuilding}
        />
      </>
    );
  }

  const row = (d: IclockDevice) => (
    <DeviceRow
      device={d}
      onClaim={() => setClaiming(d)}
      onChangeRole={() => setChangingRole(d)}
      onMove={() => setMovingBuilding(d)}
      canMove={buildings.length > 1}
    />
  );

  return (
    <div className="space-y-6">
      <BuildingsBar
        buildings={buildings}
        onRename={setRenaming}
        onCreate={() => setCreatingBuilding(true)}
      />

      {unclaimed.length > 0 ? (
        <Card className="border-warning/25 bg-warning/5 p-6">
          <div className="mb-4">
            <h2 className="text-base font-semibold">Waiting to be adopted</h2>
            <p className="text-sm text-muted-foreground">
              These terminals are talking to the server and their punches are being captured, but
              nothing is attributed until they are claimed to a building and given a role.
            </p>
          </div>
          <ul className="divide-y divide-border overflow-hidden rounded-xl border border-border bg-card">
            {unclaimed.map((d) => (
              <li key={d.id}>{row(d)}</li>
            ))}
          </ul>
        </Card>
      ) : null}

      <Card className="p-6">
        <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
          <div className="flex flex-wrap items-center gap-3">
            <h2 className="text-base font-semibold">Claimed terminals</h2>
            <CommandChannelBadge />
          </div>
          <ViewToggle value={mode} onChange={setMode} />
        </div>
        {claimed.length === 0 ? (
          <EmptyState
            icon={Router}
            title="Nothing claimed yet"
            description="Claim a terminal above to start attributing its punches."
            className="py-10"
          />
        ) : (
          <GroupedList
            items={claimed}
            grouping={{
              keyOf: (d) => d.siteName,
              // Fleet health reads by last contact, so the freshest building sorts first.
              timeOf: (d) => d.lastSeenAt,
              ungroupedLabel: 'No building',
              mode,
            }}
            storageKey="devices.building"
            itemKey={(d) => d.id}
            renderItem={row}
          />
        )}
      </Card>

      <ClaimDialog
        device={claiming}
        buildings={buildings}
        defaultSiteId={site?.id ?? buildings[0]?.id ?? ''}
        open={Boolean(claiming)}
        onOpenChange={(o) => !o && setClaiming(null)}
      />
      <ChangeRoleDialog
        device={changingRole}
        open={Boolean(changingRole)}
        onOpenChange={(o) => !o && setChangingRole(null)}
      />
      <MoveBuildingDialog
        device={movingBuilding}
        buildings={buildings}
        open={Boolean(movingBuilding)}
        onOpenChange={(o) => !o && setMovingBuilding(null)}
      />
      <BuildingDialogs
        renaming={renaming}
        setRenaming={setRenaming}
        creating={creatingBuilding}
        setCreating={setCreatingBuilding}
      />
    </div>
  );
}

function DeviceRow({
  device,
  onClaim,
  onChangeRole,
  onMove,
  canMove,
}: {
  device: IclockDevice;
  onClaim: () => void;
  onChangeRole: () => void;
  onMove: () => void;
  canMove: boolean;
}) {
  const qc = useQueryClient();
  const unclaim = useApiMutation(() => unclaimDevice(device.id), {
    successMessage: 'Unclaimed. Punches are still captured, but no longer attributed.',
    onSuccess: () => qc.invalidateQueries({ queryKey: iclockKeys.root }),
  });
  const claimedDevice = device.status === 'CLAIMED';
  const [showLog, setShowLog] = React.useState(false);

  return (
    <div>
    <div className="flex flex-wrap items-center gap-3 px-3 py-2.5">
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <span className="truncate text-sm font-medium">{device.name ?? device.serialNumber}</span>
          <DeviceHealthBadge
            healthy={claimedDevice && minutesSince(device.lastSeenAt) <= HEALTHY_MINUTES}
            minutesSinceSeen={minutesSince(device.lastSeenAt)}
            lastSeenAt={device.lastSeenAt}
            status={device.status}
          />
          <DeviceRoleBadge area={device.area} direction={device.direction} status={device.status} />
        </div>
        <div className="mt-0.5 truncate font-mono text-xs text-muted-foreground">
          {device.serialNumber}
          {device.siteName ? <span className="ml-2">{device.siteName}</span> : null}
          <span className="ml-2">{device.rawPunchCount.toLocaleString()} captured</span>
          {device.lastSeenAt ? (
            <span className="ml-2" title={istDateTime(device.lastSeenAt)}>
              seen {relativeTime(device.lastSeenAt)}
            </span>
          ) : null}
        </div>
      </div>

      {claimedDevice ? <SyncTimeButton deviceId={device.id} /> : null}
      {claimedDevice ? <QueryUsersButton deviceId={device.id} /> : null}

      {claimedDevice ? (
        <RowMenu
          label={`Actions for ${device.name ?? device.serialNumber}`}
          actions={[
            { label: 'Change role…', icon: Tag, onSelect: onChangeRole },
            {
              label: showLog ? 'Hide command log' : 'Command log',
              icon: ScrollText,
              onSelect: () => setShowLog((v) => !v),
            },
            ...(canMove ? [{ label: 'Move to another building…', icon: MoveRight, onSelect: onMove }] : []),
            {
              label: 'Unclaim',
              icon: ShieldOff,
              onSelect: () => unclaim.mutate(),
              disabled: unclaim.isPending,
              danger: true,
            },
          ]}
        />
      ) : (
        <Button variant="outline" size="sm" onClick={onClaim}>
          <ShieldCheck className="size-4" />
          Claim
        </Button>
      )}
    </div>

    {/* The command log lives under its own terminal: "what have we told this device" is a question
        about one device, and a fleet-wide list would bury the row that matters. */}
    {claimedDevice && showLog ? (
      <div className="border-t border-border bg-muted/30">
        <DeviceCommandLog deviceId={device.id} />
      </div>
    ) : null}
    </div>
  );
}

/** The four-role picker, shared by claim and change-role so the vocabulary cannot diverge. */
function RolePicker({
  value,
  onChange,
}: {
  value: DeviceRole;
  onChange: (r: DeviceRole) => void;
}) {
  return (
    <div className="grid gap-2 sm:grid-cols-2">
      {DEVICE_ROLES.map((r) => {
        const active = r.value === value;
        return (
          <button
            key={r.value}
            type="button"
            onClick={() => onChange(r.value)}
            aria-pressed={active}
            className={cn(
              'flex items-start gap-2 rounded-xl border p-3 text-left transition-colors',
              'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
              active
                ? 'border-primary bg-primary/5'
                : 'border-border bg-card hover:bg-accent',
            )}
          >
            <r.icon className={cn('mt-0.5 size-4 shrink-0', active ? 'text-primary' : 'text-muted-foreground')} />
            <span className="min-w-0">
              <span className="block text-sm font-medium">{r.label}</span>
              <span className="block text-xs text-muted-foreground">{r.hint}</span>
            </span>
          </button>
        );
      })}
    </div>
  );
}

function ClaimDialog({
  device,
  buildings,
  defaultSiteId,
  open,
  onOpenChange,
}: {
  device: IclockDevice | null;
  buildings: IclockSite[];
  defaultSiteId: string;
  open: boolean;
  onOpenChange: (o: boolean) => void;
}) {
  const qc = useQueryClient();
  const [siteId, setSiteId] = React.useState(defaultSiteId);
  const [role, setRole] = React.useState<DeviceRole>('GATE_IN');
  const [name, setName] = React.useState('');

  React.useEffect(() => {
    if (device) {
      setSiteId(device.siteId ?? defaultSiteId);
      setRole(roleOf(device.area, device.direction)?.value ?? 'GATE_IN');
      setName(device.name ?? '');
    }
  }, [device, defaultSiteId]);

  const create = useApiMutation((n: string) => createSite({ name: n }), {
    successMessage: 'Building created.',
    onSuccess: (s) => {
      qc.invalidateQueries({ queryKey: iclockKeys.sites() });
      setSiteId(s.id);
    },
  });

  const claim = useApiMutation(
    () => {
      const meta = roleByValue(role);
      return claimDevice(device?.id as string, {
        siteId,
        area: meta.area,
        direction: meta.direction,
        name: name.trim() || undefined,
      });
    },
    {
      successMessage: 'Claimed. Its punches will be attributed from now on.',
      onSuccess: () => {
        qc.invalidateQueries({ queryKey: iclockKeys.root });
        onOpenChange(false);
      },
    },
  );

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>Claim {device?.serialNumber}</DialogTitle>
          <DialogDescription>
            Adopts this terminal into a building and gives it a role. Punches it has already sent are
            kept — claiming does not backfill them, but re-resolving from the People screen will.
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
            <span className="text-sm font-medium">Building</span>
            <Combobox
              ariaLabel="Building"
              options={buildings.map((b) => ({ value: b.id, label: b.name }))}
              value={siteId}
              onChange={setSiteId}
              onCreate={(n) => create.mutate(n)}
              createLabel={(n) => `Create building “${n}”`}
              className="w-full"
            />
          </div>
          <div className="space-y-1.5">
            <span className="text-sm font-medium">Role</span>
            <RolePicker value={role} onChange={setRole} />
          </div>
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
          <div className="flex justify-end gap-2">
            <Button type="button" variant="ghost" onClick={() => onOpenChange(false)}>
              Cancel
            </Button>
            <Button type="submit" disabled={!siteId || claim.isPending}>
              <ShieldCheck className="size-4" />
              {claim.isPending ? 'Claiming…' : 'Claim'}
            </Button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}

function ChangeRoleDialog({
  device,
  open,
  onOpenChange,
}: {
  device: IclockDevice | null;
  open: boolean;
  onOpenChange: (o: boolean) => void;
}) {
  const qc = useQueryClient();
  const [role, setRole] = React.useState<DeviceRole>('GATE_IN');

  React.useEffect(() => {
    if (device) setRole(roleOf(device.area, device.direction)?.value ?? 'GATE_IN');
  }, [device]);

  const save = useApiMutation(
    () => {
      const meta = roleByValue(role);
      return changeDeviceRole(device?.id as string, { area: meta.area, direction: meta.direction });
    },
    {
      successMessage: 'Role changed — applies from now on.',
      onSuccess: () => {
        qc.invalidateQueries({ queryKey: iclockKeys.root });
        onOpenChange(false);
      },
    },
  );

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>Change role — {device?.name ?? device?.serialNumber}</DialogTitle>
          <DialogDescription>
            This takes effect from now on. Punches already recorded keep the role they were made
            under — the terminal was mislabelled, but those punches really did happen the way they
            were recorded, and rewriting them would change attendance history to match a settings fix.
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-4">
          <RolePicker value={role} onChange={setRole} />
          <div className="flex justify-end gap-2">
            <Button variant="ghost" onClick={() => onOpenChange(false)}>
              Cancel
            </Button>
            <Button onClick={() => save.mutate()} disabled={save.isPending}>
              {save.isPending ? 'Saving…' : 'Change role'}
            </Button>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}

function MoveBuildingDialog({
  device,
  buildings,
  open,
  onOpenChange,
}: {
  device: IclockDevice | null;
  buildings: IclockSite[];
  open: boolean;
  onOpenChange: (o: boolean) => void;
}) {
  const qc = useQueryClient();
  const [siteId, setSiteId] = React.useState('');

  React.useEffect(() => {
    if (device) setSiteId(device.siteId ?? '');
  }, [device]);

  const move = useApiMutation(() => moveDeviceBuilding(device?.id as string, siteId), {
    successMessage: 'Moved — applies from now on.',
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: iclockKeys.root });
      onOpenChange(false);
    },
  });

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>Move {device?.name ?? device?.serialNumber}</DialogTitle>
          <DialogDescription>
            Applies from now on. Punches already recorded stay with the building they were made in — a
            terminal moved today did not retroactively record last week&rsquo;s punches somewhere else.
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-4">
          <Combobox
            ariaLabel="Building"
            options={buildings.map((b) => ({ value: b.id, label: b.name }))}
            value={siteId}
            onChange={setSiteId}
            className="w-full"
          />
          <div className="flex justify-end gap-2">
            <Button variant="ghost" onClick={() => onOpenChange(false)}>
              Cancel
            </Button>
            <Button
              onClick={() => move.mutate()}
              disabled={!siteId || siteId === device?.siteId || move.isPending}
            >
              <MoveRight className="size-4" />
              {move.isPending ? 'Moving…' : 'Move'}
            </Button>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}

/** Buildings, with their companies and terminal counts. Create and rename live here. */
function BuildingsBar({
  buildings,
  onRename,
  onCreate,
}: {
  buildings: IclockSite[];
  onRename: (s: IclockSite) => void;
  onCreate: () => void;
}) {
  return (
    <Card className="p-6">
      <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
        <h2 className="inline-flex items-center gap-2 text-base font-semibold">
          <Building2 className="size-4 text-muted-foreground" />
          Buildings
        </h2>
        <Button variant="outline" size="sm" onClick={onCreate}>
          New building
        </Button>
      </div>
      {buildings.length === 0 ? (
        <p className="text-sm text-muted-foreground">
          No buildings yet. A building groups the companies working there and the terminals mounted in
          it — one has to exist before a terminal can be claimed.
        </p>
      ) : (
        <ul className="divide-y divide-border">
          {buildings.map((b) => (
            <li key={b.id} className="flex flex-wrap items-center gap-3 py-2.5 first:pt-0 last:pb-0">
              <span className="min-w-0 flex-1 truncate text-sm font-medium">{b.name}</span>
              <span className="shrink-0 text-xs tabular-nums text-muted-foreground">
                {b.companyCount} {b.companyCount === 1 ? 'company' : 'companies'} ·{' '}
                {b.deviceCount} {b.deviceCount === 1 ? 'terminal' : 'terminals'}
              </span>
              <RowMenu
                label={`Actions for ${b.name}`}
                actions={[{ label: 'Rename…', icon: Pencil, onSelect: () => onRename(b) }]}
              />
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}

function BuildingDialogs({
  renaming,
  setRenaming,
  creating,
  setCreating,
}: {
  renaming: IclockSite | null;
  setRenaming: (s: IclockSite | null) => void;
  creating: boolean;
  setCreating: (b: boolean) => void;
}) {
  const qc = useQueryClient();
  const [name, setName] = React.useState('');

  React.useEffect(() => {
    setName(renaming?.name ?? '');
  }, [renaming]);
  React.useEffect(() => {
    if (creating) setName('');
  }, [creating]);

  const done = () => {
    qc.invalidateQueries({ queryKey: iclockKeys.root });
    setRenaming(null);
    setCreating(false);
  };

  const rename = useApiMutation(() => renameSite(renaming?.id as string, name.trim()), {
    successMessage: 'Building renamed.',
    onSuccess: done,
  });
  const create = useApiMutation(() => createSite({ name: name.trim() }), {
    successMessage: 'Building created.',
    onSuccess: done,
  });

  const open = Boolean(renaming) || creating;
  const isRename = Boolean(renaming);

  return (
    <Dialog
      open={open}
      onOpenChange={(o) => {
        if (!o) {
          setRenaming(null);
          setCreating(false);
        }
      }}
    >
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>{isRename ? `Rename ${renaming?.name}` : 'New building'}</DialogTitle>
          <DialogDescription>
            {isRename
              ? 'Renaming affects every screen immediately. Terminals, companies and punches are untouched.'
              : 'A building groups the companies working there and the terminals mounted in it.'}
          </DialogDescription>
        </DialogHeader>
        <form
          className="space-y-4"
          onSubmit={(e) => {
            e.preventDefault();
            if (isRename) rename.mutate();
            else create.mutate();
          }}
        >
          <Input
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="Orion Towers"
            aria-label="Building name"
            autoFocus
          />
          <div className="flex justify-end gap-2">
            <Button
              type="button"
              variant="ghost"
              onClick={() => {
                setRenaming(null);
                setCreating(false);
              }}
            >
              Cancel
            </Button>
            <Button type="submit" disabled={!name.trim() || rename.isPending || create.isPending}>
              {isRename ? 'Rename' : 'Create'}
            </Button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}
