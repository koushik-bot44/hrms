'use client';

import * as React from 'react';
import { useQueryClient } from '@tanstack/react-query';
import type { CompanySummary } from '@/lib/contract';
import { deleteCompany } from '@/lib/api/companies';
import { useApiMutation } from '@/lib/api/hooks';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';

/** Type-the-name confirm to archive a company; warns plainly that everyone under it loses access. */
export function DeleteCompanyDialog({
  company,
  onClose,
}: {
  company: CompanySummary | null;
  onClose: () => void;
}) {
  const queryClient = useQueryClient();
  const [confirm, setConfirm] = React.useState('');

  React.useEffect(() => setConfirm(''), [company]);

  const mutation = useApiMutation(() => deleteCompany(company!.id), {
    successMessage: (c) => `“${c.name}” archived`,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['companies'] });
      void queryClient.invalidateQueries({ queryKey: ['companies', 'deleted'] });
      onClose();
    },
  });

  const nameMatches =
    company != null && confirm.trim().toLowerCase() === company.name.trim().toLowerCase();

  return (
    <Dialog open={Boolean(company)} onOpenChange={(open) => (open ? null : onClose())}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Archive “{company?.name}”?</DialogTitle>
          <DialogDescription>
            Its Company Admin, HR, Managers and employees will <strong>immediately lose access</strong>{' '}
            and be signed out. Nothing is permanently deleted — its data and audit trail are kept, and
            you can <strong>restore</strong> it later.
          </DialogDescription>
        </DialogHeader>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            if (nameMatches) mutation.mutate();
          }}
          className="space-y-4"
        >
          <div className="space-y-1.5">
            <label htmlFor="confirm-company" className="text-sm font-medium">
              Type <span className="font-semibold">{company?.name}</span> to confirm
            </label>
            <Input
              id="confirm-company"
              value={confirm}
              onChange={(e) => setConfirm(e.target.value)}
              autoComplete="off"
              placeholder={company?.name}
            />
          </div>
          <div className="flex justify-end gap-2 pt-1">
            <Button type="button" variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" variant="destructive" disabled={!nameMatches || mutation.isPending}>
              {mutation.isPending ? 'Archiving…' : 'Archive company'}
            </Button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}
