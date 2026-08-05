'use client';

import * as React from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { FileSignature } from 'lucide-react';
import { AgreementTypeValues, AGREEMENT_TITLES, type AgreementType } from '@/lib/contract';
import { sendAgreements } from '@/lib/api/agreements';
import { useApiMutation } from '@/lib/api/hooks';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog';

/**
 * HR sends the standard post-approval agreements (§3.5) — AUP, NDA & Non-Compete, Notice Period — to an
 * APPROVED employee. The three are listed with checkboxes (Rider A): already-sent types show disabled, the
 * rest default checked. Send creates only the checked types the employee does not already have, so HR can
 * send some now and the rest later; own-scope + per-type idempotency are enforced server-side.
 */
export function SendAgreementsDialog({
  employeeId,
  existingTypes = [],
}: {
  employeeId: string;
  /** Types already sent to this employee — shown disabled, not re-sendable. */
  existingTypes?: AgreementType[];
}) {
  const queryClient = useQueryClient();
  const [open, setOpen] = React.useState(false);
  const [selected, setSelected] = React.useState<Set<AgreementType>>(new Set());

  // On open, pre-check exactly the not-yet-sent types.
  React.useEffect(() => {
    if (open) {
      setSelected(new Set(AgreementTypeValues.filter((t) => !existingTypes.includes(t))));
    }
  }, [open, existingTypes]);

  const send = useApiMutation(() => sendAgreements(employeeId, [...selected]), {
    successMessage: 'Agreements sent to the employee',
    onSuccess: () => {
      setOpen(false);
      void queryClient.invalidateQueries({ queryKey: ['hr-record', employeeId] });
    },
  });

  const toggle = (t: AgreementType) =>
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(t)) next.delete(t);
      else next.add(t);
      return next;
    });

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button type="button" variant="outline" size="sm">
          <FileSignature className="size-4" />
          Send agreements
        </Button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Send agreements</DialogTitle>
          <DialogDescription>
            Choose which agreements to send. The employee will read, fill, and sign each one. You can send the
            rest later.
          </DialogDescription>
        </DialogHeader>
        <ul className="space-y-1">
          {AgreementTypeValues.map((t) => {
            const already = existingTypes.includes(t);
            return (
              <li key={t}>
                <label
                  className="flex items-center gap-3 rounded-md border p-3 text-sm has-[:disabled]:opacity-60"
                  htmlFor={`send-${t}`}
                >
                  <input
                    id={`send-${t}`}
                    type="checkbox"
                    className="size-4"
                    checked={already || selected.has(t)}
                    disabled={already}
                    onChange={() => toggle(t)}
                  />
                  <span className="min-w-0 flex-1">{AGREEMENT_TITLES[t]}</span>
                  {already ? <Badge variant="neutral">Already sent</Badge> : null}
                </label>
              </li>
            );
          })}
        </ul>
        <div className="flex justify-end gap-2 pt-2">
          <Button type="button" variant="ghost" onClick={() => setOpen(false)} disabled={send.isPending}>
            Cancel
          </Button>
          <Button
            type="button"
            onClick={() => send.mutate()}
            disabled={selected.size === 0 || send.isPending}
          >
            {send.isPending ? 'Sending…' : `Send ${selected.size || ''}`.trim()}
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
