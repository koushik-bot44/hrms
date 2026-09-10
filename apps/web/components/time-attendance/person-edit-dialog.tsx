'use client';

import * as React from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { TriangleAlert } from 'lucide-react';
import {
  editPerson,
  iclockKeys,
  listCompanyOptions,
  listTeamOptions,
  SHIFT_LABELS,
  upsertPerson,
  type CompanyOption,
  type IclockPerson,
} from '@/lib/api/iclock';
import { useApiMutation, useApiQuery } from '@/lib/api/hooks';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Combobox } from '@/components/console/combobox';
import {
  CommandChannelBadge,
  EnrolmentStatePanel,
  PushNameButton,
} from './device-commands';

/**
 * One dialog for editing a person's details, shared by the People row menu and the inbox's
 * "add person" flow.
 *
 * <p>Shared deliberately. Two dialogs would drift, and the one reached from the inbox is the one used
 * on a brand-new person — precisely where a free-text company field does the most damage, because
 * that row then becomes the seed of a company nobody meant to create.
 *
 * COMPANY IS A CLOSED LIST. It was free text, which is how a roster grows "Screatives", "screatives"
 * and "Screatives Software Services" as three companies; the import already carries an alias map to
 * undo exactly that damage from the seed data, and there is no reason to keep making more by hand.
 *
 * TEAM IS AN OPEN ONE. Teams are labels the operator invents as the floor reorganises, so a new one
 * is legitimate — but the existing values are offered first, which is what stops one team becoming
 * three by typo.
 */
export function PersonEditDialog({
  siteId,
  person,
  presetPin,
  open,
  onOpenChange,
  onSaved,
}: {
  siteId: string;
  /** The person being edited, or null when creating one from a pin. */
  person: IclockPerson | null;
  /** Pre-fills the pin when the dialog is opened from the unmapped-pin inbox. */
  presetPin?: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onSaved?: () => void;
}) {
  const qc = useQueryClient();
  const creating = person == null;

  const [name, setName] = React.useState('');
  const [email, setEmail] = React.useState('');
  const [companyId, setCompanyId] = React.useState<string | null>(null);
  const [team, setTeam] = React.useState<string | null>(null);
  const [shift, setShift] = React.useState<string>('NIGHT');
  const [pinInput, setPinInput] = React.useState('');
  const [excluded, setExcluded] = React.useState(false);
  /** Teams typed inline; held here so the option survives until the save round-trips. */
  const [newTeams, setNewTeams] = React.useState<string[]>([]);

  const companiesQuery = useApiQuery<CompanyOption[]>(
    iclockKeys.companies(),
    (signal) => listCompanyOptions(signal),
    { retry: false, enabled: open },
  );
  const teamsQuery = useApiQuery<string[]>(
    iclockKeys.teams(siteId),
    (signal) => listTeamOptions(siteId, signal),
    { retry: false, enabled: open },
  );

  // Seed from the person each time the dialog opens, so a cancelled edit never lingers into the next.
  React.useEffect(() => {
    if (!open) return;
    setName(person?.name ?? '');
    setEmail(person?.email ?? '');
    setCompanyId(person?.companyId ?? null);
    setTeam(person?.team ?? null);
    setShift(person?.shiftProfile ?? 'NIGHT');
    setPinInput(person?.pin ?? presetPin ?? '');
    setExcluded(person?.excludedFromReports ?? false);
    setNewTeams([]);
  }, [open, person]);

  const teamOptions = React.useMemo(() => {
    const all = new Set<string>([...(teamsQuery.data ?? []), ...newTeams]);
    if (team) all.add(team);
    return [...all].sort((a, b) => a.localeCompare(b)).map((t) => ({ value: t, label: t }));
  }, [teamsQuery.data, newTeams, team]);

  const companyOptions = React.useMemo(
    () => (companiesQuery.data ?? []).map((c) => ({ value: c.id, label: c.name })),
    [companiesQuery.data],
  );

  const save = useApiMutation(
    () => {
      const body = {
        pin,
        name: name.trim(),
        email: email.trim(),
        // An empty string is meaningful here: it CLEARS the field. Sending null would mean "leave it
        // alone", so a deliberate blanking would silently do nothing.
        companyId: companyId ?? '',
        team: team ?? '',
        shiftProfile: shift,
        excludedFromReports: excluded,
      };
      return creating ? upsertPerson(siteId, body) : editPerson(person!.id, body);
    },
    {
      successMessage: creating ? 'Person added.' : 'Saved.',
      onSuccess: () => {
        qc.invalidateQueries({ queryKey: iclockKeys.root });
        onOpenChange(false);
        onSaved?.();
      },
    },
  );

  // A pin arrives one of three ways: an existing person's own, the inbox handing one over, or
  // somebody typing it. The third had no field at all, so Save could never enable and "Add person"
  // was unreachable from the People screen.
  const pin = (person?.pin ?? presetPin ?? pinInput).trim();
  const pinLooksUsable = /^\d+$/.test(pin) && /[1-9]/.test(pin);
  const canSave = pinLooksUsable && !save.isPending;

  return (
    <>
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{creating ? 'Add person' : 'Edit person'}</DialogTitle>
          <DialogDescription>
            {creating
              ? `Pin ${pin} will be added to this building's roster and will start resolving on the next punch.`
              : `Pin ${pin}. Changing the company changes which report they appear in and which company is copied on their warning letter.`}
          </DialogDescription>
        </DialogHeader>

        <form
          className="space-y-4"
          onSubmit={(e) => {
            e.preventDefault();
            if (canSave) save.mutate();
          }}
        >
          {creating && !presetPin ? (
            <div className="space-y-1.5">
              <label className="text-sm font-medium" htmlFor="pe-pin">
                Pin <span className="text-destructive">*</span>
              </label>
              <Input
                id="pe-pin"
                inputMode="numeric"
                value={pinInput}
                onChange={(e) => setPinInput(e.target.value)}
                placeholder="e.g. 22193"
                autoFocus
              />
              <p className="text-xs text-muted-foreground">
                The number the terminal knows them by. Digits only. A pin already on this
                building&rsquo;s roster is refused rather than overwritten.
              </p>
            </div>
          ) : null}

          <div className="space-y-1.5">
            <label className="text-sm font-medium" htmlFor="pe-name">Name</label>
            <Input
              id="pe-name"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="Unnamed"
            />
            <p className="text-xs text-muted-foreground">
              Blank is allowed — somebody seeded from punch history genuinely has no name yet.
            </p>
          </div>

          <div className="space-y-1.5">
            <label className="text-sm font-medium" htmlFor="pe-email">Email</label>
            <Input
              id="pe-email"
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="name@company.com"
            />
            {person?.duplicateEmail ? (
              <p className="inline-flex items-center gap-1.5 text-xs text-warning">
                <TriangleAlert className="size-3.5" aria-hidden />
                Shared with another person at this building. Flagged, never blocked.
              </p>
            ) : null}
          </div>

          <div className="space-y-1.5">
            <label className="text-sm font-medium">Company</label>
            <Combobox
              ariaLabel="Company"
              options={companyOptions}
              value={companyId}
              onChange={(v) => setCompanyId(v || null)}
              emptyOptionLabel="No company"
              placeholder={companiesQuery.isLoading ? 'Loading…' : 'Select a company…'}
              searchPlaceholder="Search companies…"
              disabled={companiesQuery.isLoading}
            />
            <p className="text-xs text-muted-foreground">
              Active IHRMS companies only. New companies are created in Companies, not here.
            </p>
          </div>

          <div className="space-y-1.5">
            <label className="text-sm font-medium">Team</label>
            <Combobox
              ariaLabel="Team"
              options={teamOptions}
              value={team}
              onChange={(v) => setTeam(v || null)}
              onCreate={(n) => {
                const t = n.trim();
                if (!t) return;
                setNewTeams((prev) => (prev.includes(t) ? prev : [...prev, t]));
                setTeam(t);
              }}
              createLabel={(n) => `Add new team “${n}”`}
              emptyOptionLabel="No team"
              placeholder={teamsQuery.isLoading ? 'Loading…' : 'Select or add a team…'}
              searchPlaceholder="Search teams…"
              disabled={teamsQuery.isLoading}
            />
            <p className="text-xs text-muted-foreground">
              Teams already used at this building. Type a new name to add one.
            </p>
          </div>

          <div className="space-y-1.5">
            <label className="text-sm font-medium">Shift</label>
            <Combobox
              ariaLabel="Shift"
              options={Object.entries(SHIFT_LABELS).map(([value, label]) => ({ value, label }))}
              value={shift}
              onChange={(v) => setShift(v || 'NIGHT')}
              placeholder="Select a shift…"
              searchPlaceholder="Search shifts…"
            />
            <p className="text-xs text-muted-foreground">
              Their shift decides which day their punches file under, when they count as late, and
              when a long absence is worth alerting on. Changing it re-dates this payroll cycle;
              earlier cycles are left alone.
            </p>
          </div>

          <label className="flex items-start gap-2.5 rounded-lg border border-border p-3">
            <input
              type="checkbox"
              className="mt-0.5 size-4 accent-primary"
              checked={excluded}
              onChange={(e) => setExcluded(e.target.checked)}
            />
            <span className="space-y-0.5">
              <span className="block text-sm font-medium">Leave out of reports</span>
              {/* Reporting only. Their punches still resolve, still promote, still show on the live
                  board — anything else would look like the terminal had stopped seeing them. */}
              <span className="block text-xs text-muted-foreground">
                Keeps them off the monthly report, payroll export and warning letters. They still
                appear on the live board and their punches are still recorded.
              </span>
            </span>
          </label>

          {!creating && person ? (
            <div className="space-y-3">
              <div className="space-y-2 rounded-lg border border-border bg-muted/40 p-3">
                <div className="flex flex-wrap items-center gap-2">
                  <PushNameButton personId={person.id} disabled={!name.trim()} />
                  <CommandChannelBadge />
                </div>
                <p className="text-xs text-muted-foreground">
                  Sends this name to every terminal at their building. Save first — the push uses
                  the SAVED name, not what is typed here.
                </p>
              </div>
              <EnrolmentStatePanel
                personId={person.id}
                siteId={siteId}
                personName={person.name ?? person.pin}
              />
            </div>
          ) : null}

          <div className="flex flex-wrap justify-end gap-2 border-t border-border pt-4">
            <Button type="button" variant="ghost" onClick={() => onOpenChange(false)}>
              Cancel
            </Button>
            <Button type="submit" disabled={!canSave}>
              {save.isPending ? 'Saving…' : creating ? 'Add person' : 'Save'}
            </Button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
    </>
  );
}
