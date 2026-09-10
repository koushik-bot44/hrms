'use client';

import * as React from 'react';
import { useQueryClient } from '@tanstack/react-query';
import {
  Check,
  Clock,
  Fingerprint,
  RadioTower,
  RefreshCw,
  ScanFace,
  Send,
  TriangleAlert,
  X,
} from 'lucide-react';
import {
  BIO_FACE,
  BIO_FINGERPRINT,
  enrolOnDevice,
  getCommandLog,
  getEnrolmentState,
  getCommandStatus,
  iclockKeys,
  listDevices,
  pushName,
  queryDeviceUsers,
  resyncBiometrics,
  syncDeviceTime,
  type CommandStatus,
  type DeviceCommand,
  type EnrolmentState,
  type EnrolmentTrigger,
  type IclockDevice,
} from '@/lib/api/iclock';
import { useApiMutation, useApiQuery } from '@/lib/api/hooks';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { LoadingSkeleton } from '@/components/loading-skeleton';
import { istDateTime, relativeTime } from '@/lib/date';
import { cn } from '@/lib/utils';

/**
 * The console surface for the device command channel — the one path that WRITES to hardware.
 *
 * <p>Every control here is an explicit button. Nothing queues as a side effect of saving a person,
 * which is the whole safety story: an operator asks for a push, by name, and the audit entry records
 * who asked.
 */

const STATUS_TONE: Record<DeviceCommand['status'], string> = {
  PENDING: 'text-muted-foreground',
  SENT: 'text-primary',
  ACKED: 'text-success',
  FAILED: 'text-destructive',
};

function StatusIcon({ status }: { status: DeviceCommand['status'] }) {
  if (status === 'ACKED') return <Check className="size-3.5" aria-hidden />;
  if (status === 'FAILED') return <X className="size-3.5" aria-hidden />;
  if (status === 'SENT') return <Send className="size-3.5" aria-hidden />;
  return <Clock className="size-3.5" aria-hidden />;
}

/**
 * Whether the channel is open, stated plainly.
 *
 * <p>Shown wherever a command can be queued. With the switch off a command still queues and simply
 * waits, which is correct but invisible — an operator who pressed a button and saw nothing happen
 * would reasonably conclude the button was broken.
 */
export function CommandChannelBadge() {
  const query = useApiQuery<CommandStatus>(
    iclockKeys.commandStatus(),
    (signal) => getCommandStatus(signal),
    { retry: false },
  );
  if (!query.data) return null;
  const { enabled, outstanding } = query.data;
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1.5 rounded-full border px-2.5 py-0.5 text-xs',
        enabled
          ? 'border-success/30 bg-success/10 text-success'
          : 'border-border bg-muted text-muted-foreground',
      )}
      title={
        enabled
          ? 'Commands are being delivered to terminals.'
          : 'Commands will queue but nothing is sent until the channel is switched on.'
      }
    >
      <RadioTower className="size-3" aria-hidden />
      {enabled ? 'Device channel open' : 'Device channel closed'}
      {outstanding > 0 ? ` · ${outstanding} waiting` : null}
    </span>
  );
}

/** "Update on device(s)" — pushes one person's name to their building's terminals. */
export function PushNameButton({
  personId,
  disabled,
  className,
}: {
  personId: string;
  disabled?: boolean;
  className?: string;
}) {
  const qc = useQueryClient();
  const push = useApiMutation(() => pushName(personId), {
    successMessage: (queued: Array<Record<string, unknown>>) =>
      `Queued on ${queued.length} terminal${queued.length === 1 ? '' : 's'}. Device screens update on their next poll.`,
    onSuccess: () => qc.invalidateQueries({ queryKey: iclockKeys.root }),
  });

  return (
    <Button
      type="button"
      variant="outline"
      size="sm"
      className={className}
      disabled={disabled || push.isPending}
      onClick={() => push.mutate()}
    >
      <RadioTower className="mr-1.5 size-4" aria-hidden />
      {push.isPending ? 'Queueing…' : 'Update on device'}
    </Button>
  );
}

/** "Sync time" — sets one terminal's clock from the server, in that building's timezone. */
export function SyncTimeButton({ deviceId }: { deviceId: string }) {
  const qc = useQueryClient();
  const sync = useApiMutation(() => syncDeviceTime(deviceId), {
    successMessage: 'Clock sync queued. Sent on the terminal’s next poll.',
    onSuccess: () => qc.invalidateQueries({ queryKey: iclockKeys.commandLog(deviceId) }),
  });
  return (
    <Button
      type="button"
      variant="outline"
      size="sm"
      disabled={sync.isPending}
      onClick={() => sync.mutate()}
    >
      <Clock className="mr-1.5 size-4" aria-hidden />
      {sync.isPending ? 'Queueing…' : 'Sync time'}
    </Button>
  );
}

/** Which finger, in the words somebody standing at a terminal would use. */
const FINGERS = [
  { index: 0, label: 'Right index' },
  { index: 1, label: 'Right thumb' },
  { index: 2, label: 'Right middle' },
  { index: 3, label: 'Left index' },
  { index: 4, label: 'Left thumb' },
  { index: 5, label: 'Left middle' },
  { index: 6, label: 'Right ring' },
  { index: 7, label: 'Right little' },
  { index: 8, label: 'Left ring' },
  { index: 9, label: 'Left little' },
];

/** What the console says about one terminal's hold on a person. */
const ENROLMENT_TONE: Record<string, string> = {
  SOURCE: 'text-success',
  PRESENT: 'text-success',
  PENDING: 'text-muted-foreground',
  FAILED: 'text-destructive',
  NONE: 'text-muted-foreground',
};

const ENROLMENT_WORD: Record<string, string> = {
  SOURCE: 'enrolled here',
  PRESENT: 'enrolled',
  PENDING: 'sending…',
  FAILED: 'refused',
  NONE: 'not enrolled',
};

/**
 * "Enrol on device" — arms ONE terminal to capture, after which the building looks after itself.
 *
 * <p>Two pickers and nothing else: which machine the person will walk to, and what it should ask
 * them for. Everything after the button is automatic — the terminal captures, pushes the template
 * back unprompted, and the server spreads it to the other doors.
 *
 * <p>This dialog cannot report success, and does not pretend to. The terminal acknowledges that it
 * understood the command; whether a finger arrived depends on somebody being there to give one.
 */
export function EnrolOnDeviceDialog({
  personId,
  personName,
  siteId,
  open,
  onOpenChange,
}: {
  personId: string;
  personName: string;
  siteId: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const qc = useQueryClient();
  const [deviceId, setDeviceId] = React.useState('');
  const [bioType, setBioType] = React.useState<number>(BIO_FINGERPRINT);
  const [finger, setFinger] = React.useState(0);
  const [sent, setSent] = React.useState<EnrolmentTrigger | null>(null);

  const devicesQuery = useApiQuery<IclockDevice[]>(
    iclockKeys.devices(),
    (signal) => listDevices(undefined, signal),
    { retry: false },
  );
  const here = (devicesQuery.data ?? []).filter(
    (d) => d.status === 'CLAIMED' && d.siteId === siteId,
  );

  React.useEffect(() => {
    if (!open) {
      setSent(null);
      setFinger(0);
      setBioType(BIO_FINGERPRINT);
    }
  }, [open]);
  React.useEffect(() => {
    if (open && !deviceId && here.length > 0) setDeviceId(here[0].id);
  }, [open, deviceId, here]);

  const trigger = useApiMutation(() => enrolOnDevice(personId, deviceId, bioType, finger), {
    onSuccess: (t: EnrolmentTrigger) => {
      setSent(t);
      qc.invalidateQueries({ queryKey: iclockKeys.root });
    },
  });

  const face = bioType === BIO_FACE;
  const target = here.find((d) => d.id === deviceId);
  const others = here.length - 1;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Enrol {personName}</DialogTitle>
          <DialogDescription>
            Arms one terminal to capture. What it captures is copied to the other terminals in the
            building automatically.
          </DialogDescription>
        </DialogHeader>

        {sent ? (
          <div className="space-y-3 text-sm">
            <div className="rounded-lg border border-border bg-card p-3">
              <p>
                <strong>{sent.deviceName}</strong> opens capture on its next poll — within about
                half a minute.
              </p>
              <p className="mt-2 text-muted-foreground">
                {sent.personName} {sent.bioType === BIO_FACE
                  ? 'looks at the camera when the screen asks.'
                  : 'presses the same finger three times when the screen asks.'}
              </p>
            </div>
            {others > 0 ? (
              <div className="rounded-lg border border-border bg-muted/40 p-3">
                <p className="inline-flex items-center gap-1.5">
                  <RadioTower className="size-3.5" aria-hidden />
                  Then it copies itself to the other {others} terminal
                  {others === 1 ? '' : 's'} here.
                </p>
                <p className="mt-1 text-xs text-muted-foreground">
                  No second visit. Watch the count on this person’s row go to {here.length} of{' '}
                  {here.length}.
                </p>
              </div>
            ) : null}
            {/* An ack is not an enrolment. Saying otherwise would send somebody away believing their
                finger works, which they find out at a gate at 7pm. */}
            <p className="text-xs text-muted-foreground">
              The terminal confirms it understood the command, which is not the same as a capture
              having happened. The proof is their next punch going through.
            </p>
            <div className="flex justify-end">
              <Button onClick={() => onOpenChange(false)}>Done</Button>
            </div>
          </div>
        ) : (
          <div className="space-y-4">
            {here.length === 0 ? (
              <p className="text-sm text-muted-foreground">
                No claimed terminal at this building to enrol on.
              </p>
            ) : (
              <>
                <label className="block space-y-1.5">
                  <span className="text-sm font-medium">Terminal</span>
                  <select
                    className="h-9 w-full rounded-md border border-border bg-background px-2 text-sm"
                    value={deviceId}
                    onChange={(e) => setDeviceId(e.target.value)}
                  >
                    {here.map((d) => (
                      <option key={d.id} value={d.id}>
                        {d.name ?? d.serialNumber}
                        {d.direction ? ' — ' + d.direction : ''}
                      </option>
                    ))}
                  </select>
                  <span className="block text-xs text-muted-foreground">
                    Whichever one they can get to. It reaches the rest by itself.
                  </span>
                </label>

                <fieldset className="space-y-1.5">
                  <legend className="text-sm font-medium">Capture</legend>
                  <div className="grid grid-cols-2 gap-2">
                    <button
                      type="button"
                      onClick={() => setBioType(BIO_FINGERPRINT)}
                      className={cn(
                        'flex items-center gap-2 rounded-md border px-3 py-2 text-sm',
                        !face
                          ? 'border-primary bg-primary/5 text-foreground'
                          : 'border-border text-muted-foreground hover:bg-muted/50',
                      )}
                    >
                      <Fingerprint className="size-4" aria-hidden />
                      Fingerprint
                    </button>
                    <button
                      type="button"
                      onClick={() => setBioType(BIO_FACE)}
                      className={cn(
                        'flex items-center gap-2 rounded-md border px-3 py-2 text-sm',
                        face
                          ? 'border-primary bg-primary/5 text-foreground'
                          : 'border-border text-muted-foreground hover:bg-muted/50',
                      )}
                    >
                      <ScanFace className="size-4" aria-hidden />
                      Face
                      <Badge variant="outline" className="ml-auto text-[10px]">beta</Badge>
                    </button>
                  </div>
                </fieldset>

                {face ? (
                  /* Marked beta because it IS: no face template has ever reached this server, and
                     the trigger has two competing spellings in the specifications. Saying so is
                     cheaper than an operator concluding the terminal is broken. */
                  <p className="inline-flex items-start gap-1.5 rounded-md border border-warning/30 bg-warning/5 p-2.5 text-xs text-warning">
                    <TriangleAlert className="mt-0.5 size-3.5 shrink-0" aria-hidden />
                    <span>
                      No face has ever been captured on this fleet, so this is the first test of it.
                      If the terminal ignores the command it shows as refused in its command log —
                      fingerprint is the proven path today.
                    </span>
                  </p>
                ) : (
                  <label className="block space-y-1.5">
                    <span className="text-sm font-medium">Finger</span>
                    <select
                      className="h-9 w-full rounded-md border border-border bg-background px-2 text-sm"
                      value={finger}
                      onChange={(e) => setFinger(Number(e.target.value))}
                    >
                      {FINGERS.map((f) => (
                        <option key={f.index} value={f.index}>
                          {f.label}
                        </option>
                      ))}
                    </select>
                    <span className="block text-xs text-muted-foreground">
                      Each finger is stored separately. Re-enrolling one replaces only that one.
                    </span>
                  </label>
                )}
              </>
            )}

            <div className="flex flex-wrap items-center justify-end gap-2 border-t border-border pt-4">
              <CommandChannelBadge />
              <div className="flex-1" />
              <Button type="button" variant="ghost" onClick={() => onOpenChange(false)}>
                Cancel
              </Button>
              <Button
                onClick={() => trigger.mutate()}
                disabled={trigger.isPending || !deviceId || here.length === 0}
              >
                {face ? (
                  <ScanFace className="mr-1.5 size-4" aria-hidden />
                ) : (
                  <Fingerprint className="mr-1.5 size-4" aria-hidden />
                )}
                {trigger.isPending
                  ? 'Sending…'
                  : target
                    ? `Arm ${target.name ?? target.serialNumber}`
                    : 'Start capture'}
              </Button>
            </div>
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}

/**
 * Where this person's biometrics actually are — "enrolled on 4 of 4", and when they are not, which
 * door is the problem.
 *
 * <p>Reads the enrolment state rather than the command log, because the log says what was ATTEMPTED
 * and the question being asked is whether this person can get through that door. The two differ
 * exactly when something has gone wrong, which is the only time anybody looks.
 */
export function EnrolmentStatePanel({
  personId,
  siteId,
  personName,
}: {
  personId: string;
  siteId: string;
  personName: string;
}) {
  const qc = useQueryClient();
  const [enrolling, setEnrolling] = React.useState(false);
  const query = useApiQuery<EnrolmentState>(
    iclockKeys.biometrics(personId),
    (signal) => getEnrolmentState(personId, signal),
    { retry: false, refetchInterval: 15000 },
  );
  const resync = useApiMutation(() => resyncBiometrics(personId), {
    successMessage: (r: { commandsQueued: number }) =>
      `Re-sending on ${r.commandsQueued} terminal${r.commandsQueued === 1 ? '' : 's'}.`,
    onSuccess: () => qc.invalidateQueries({ queryKey: iclockKeys.root }),
  });

  const state = query.data;
  const held = (state?.fingersHeld ?? 0) + (state?.facesHeld ?? 0);
  const complete = state != null && state.devices > 0 && state.enrolledOn === state.devices;

  return (
    <div className="space-y-2 rounded-lg border border-border bg-muted/40 p-3">
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-sm font-medium">Biometrics</span>
        {state ? (
          <span
            className={cn(
              'inline-flex items-center gap-1.5 text-sm tabular-nums',
              complete ? 'text-success' : 'text-muted-foreground',
            )}
          >
            <Fingerprint className="size-3.5" aria-hidden />
            enrolled on {state.enrolledOn} of {state.devices}
          </span>
        ) : null}
        <div className="flex-1" />
        <Button type="button" variant="outline" size="sm" onClick={() => setEnrolling(true)}>
          <Fingerprint className="mr-1.5 size-4" aria-hidden />
          Enrol…
        </Button>
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={resync.isPending || held === 0}
          title={
            held === 0
              ? 'Nothing to send — no template has reached the server for this person yet.'
              : 'Copy this person’s stored fingerprints to the other terminals in THEIR building.'
          }
          onClick={() => resync.mutate()}
        >
          <RefreshCw className="mr-1.5 size-4" aria-hidden />
          {resync.isPending ? 'Sending…' : 'Sync to building devices'}
        </Button>
      </div>

      {state && state.rows.length > 0 ? (
        <ul className="divide-y divide-border rounded-md border border-border bg-card text-xs">
          {state.rows.map((r) => (
            <li key={r.deviceId} className="flex flex-wrap items-center gap-x-2 gap-y-1 px-2.5 py-1.5">
              <span className="font-medium">{r.deviceName}</span>
              {r.direction ? (
                <span className="text-muted-foreground">{r.direction}</span>
              ) : null}
              <div className="flex-1" />
              {r.fingers > 0 ? (
                <span className="text-muted-foreground tabular-nums">
                  {r.fingers} finger{r.fingers === 1 ? '' : 's'}
                </span>
              ) : null}
              {r.faces > 0 ? <span className="text-muted-foreground">face</span> : null}
              <span className={ENROLMENT_TONE[r.status] ?? 'text-muted-foreground'}>
                {ENROLMENT_WORD[r.status] ?? r.status}
              </span>
              {r.failureReason ? (
                <span className="w-full text-destructive">{r.failureReason}</span>
              ) : null}
            </li>
          ))}
        </ul>
      ) : null}

      {state && held === 0 ? (
        <p className="text-xs text-muted-foreground">
          No template has reached the server for {personName} yet. It arrives by itself the moment
          they enrol at a terminal — nothing here needs to ask for it.
        </p>
      ) : null}

      <EnrolOnDeviceDialog
        personId={personId}
        personName={personName}
        siteId={siteId}
        open={enrolling}
        onOpenChange={setEnrolling}
      />
    </div>
  );
}

/**
 * "Fetch users" - asks a terminal for its own user table.
 *
 * <p>Read-only on the device. It is how the enrolment-seeding path gets a live input at all: this
 * fleet has never volunteered a USERINFO push, so the console asks for one.
 */
export function QueryUsersButton({ deviceId }: { deviceId: string }) {
  const qc = useQueryClient();
  const ask = useApiMutation(() => queryDeviceUsers(deviceId), {
    successMessage:
      'Asked the terminal for its user list. Whatever it sends back seeds pins nobody knows yet.',
    onSuccess: () => qc.invalidateQueries({ queryKey: iclockKeys.commandLog(deviceId) }),
  });
  return (
    <Button
      type="button"
      variant="outline"
      size="sm"
      disabled={ask.isPending}
      onClick={() => ask.mutate()}
    >
      <RadioTower className="mr-1.5 size-4" aria-hidden />
      {ask.isPending ? 'Asking...' : 'Fetch users'}
    </Button>
  );
}

/** The command log for one terminal, newest first. */
export function DeviceCommandLog({ deviceId }: { deviceId: string }) {
  const query = useApiQuery<DeviceCommand[]>(
    iclockKeys.commandLog(deviceId),
    (signal) => getCommandLog(deviceId, signal),
    { retry: false, refetchInterval: 15000 },
  );

  if (query.isLoading) return <LoadingSkeleton lines={4} />;
  const rows = query.data ?? [];
  if (rows.length === 0) {
    return (
      <p className="px-3 py-2 text-xs text-muted-foreground">
        Nothing sent to this terminal yet.
      </p>
    );
  }

  return (
    <ul className="divide-y divide-border text-xs">
      {rows.map((c) => (
        <li key={c.id} className="flex flex-wrap items-center gap-x-3 gap-y-1 px-3 py-2">
          <span className={cn('inline-flex items-center gap-1.5', STATUS_TONE[c.status])}>
            <StatusIcon status={c.status} />
            {c.status}
          </span>
          <Badge variant="outline" className="text-xs">{c.kind}</Badge>
          {c.pin ? <span className="font-mono text-muted-foreground">pin {c.pin}</span> : null}
          <span className="min-w-0 flex-1 truncate font-mono text-muted-foreground" title={c.payload}>
            {c.payload}
          </span>
          {c.serveCount > 1 ? (
            <span className="text-warning">served {c.serveCount}×</span>
          ) : null}
          {c.failureReason ? (
            <span className="text-destructive">{c.failureReason}</span>
          ) : null}
          <span className="text-muted-foreground" title={istDateTime(c.createdAt)}>
            {relativeTime(c.completedAt ?? c.sentAt ?? c.createdAt)}
          </span>
        </li>
      ))}
    </ul>
  );
}
