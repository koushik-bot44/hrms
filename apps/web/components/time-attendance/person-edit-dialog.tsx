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
import { CommandChannelBadge, PushNameButton } from './device-commands';

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
        pin: person?.pin ?? presetPin ?? '',
        name: name.trim(),
        email: email.trim(),
        // An empty string is meaningful here: it CLEARS the field. Sending null would mean "leave it
        // alone", so a deliberate blanking would silently do nothing.
        companyId: companyId ?? '',
        team: team ?? '',
        shiftProfile: shift,
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

  const pin = person?.pin ?? presetPin ?? '';
  const canSave = pin.length > 0 && !save.isPending;

  return (
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

          {!creating && person ? (
            <div className="flex flex-wrap items-center gap-2 rounded-lg border border-border bg-muted/40 p-3">
              <PushNameButton personId={person.id} disabled={!name.trim()} />
              <span className="text-xs text-muted-foreground">
                Sends this name to every terminal at their building. Save first — the push uses the
                saved name, not what is typed here.
              </span>
              <CommandChannelBadge />
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
  );
}
