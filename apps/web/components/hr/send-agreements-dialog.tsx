'use client';

import * as React from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { FileSignature } from 'lucide-react';
import { AgreementTypeValues, AGREEMENT_TITLES } from '@/lib/contract';
import { sendAgreements } from '@/lib/api/agreements';
import { useApiMutation } from '@/lib/api/hooks';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog';

/**
 * HR sends the standard post-approval pack (§Agreements) — AUP, NDA & Non-Compete, and Notice Period — to an
 * APPROVED employee. A confirm dialog lists the three; on send they become PENDING for the employee to read,
 * fill, and sign. Idempotent (the button is hidden once a pack exists); own-scope enforced server-side.
 */
export function SendAgreementsDialog({ employeeId }: { employeeId: string }) {
  const queryClient = useQueryClient();
  const [open, setOpen] = React.useState(false);

  const send = useApiMutation(() => sendAgreements(employeeId), {
    successMessage: 'Agreements sent to the employee',
    onSuccess: () => {
      setOpen(false);
      void queryClient.invalidateQueries({ queryKey: ['hr-record', employeeId] });
    },
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
          <DialogTitle>Send the standard agreements</DialogTitle>
          <DialogDescription>
            The employee will be asked to read, fill, and sign each of these. This can only be done once.
          </DialogDescription>
        </DialogHeader>
        <ul className="space-y-2 text-sm">
          {AgreementTypeValues.map((t) => (
            <li key={t} className="flex items-center gap-2">
              <FileSignature className="size-4 shrink-0 text-muted-foreground" aria-hidden />
              {AGREEMENT_TITLES[t]}
            </li>
          ))}
        </ul>
        <div className="flex justify-end gap-2 pt-2">
          <Button type="button" variant="ghost" onClick={() => setOpen(false)} disabled={send.isPending}>
            Cancel
          </Button>
          <Button type="button" onClick={() => send.mutate()} disabled={send.isPending}>
            {send.isPending ? 'Sending…' : 'Send agreements'}
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
