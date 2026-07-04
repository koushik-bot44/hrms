'use client';

import { useQueryClient } from '@tanstack/react-query';
import type { CompanySummary } from '@/lib/contract';
import { restoreCompany } from '@/lib/api/companies';
import { useApiMutation } from '@/lib/api/hooks';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';

/** Confirm restoring an archived company back to active (its people can sign in again). */
export function RestoreCompanyDialog({
  company,
  onClose,
}: {
  company: CompanySummary | null;
  onClose: () => void;
}) {
  const queryClient = useQueryClient();

  const mutation = useApiMutation(() => restoreCompany(company!.id), {
    successMessage: (c) => `“${c.name}” restored`,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['companies'] });
      void queryClient.invalidateQueries({ queryKey: ['companies', 'deleted'] });
      onClose();
    },
  });

  return (
    <Dialog open={Boolean(company)} onOpenChange={(open) => (open ? null : onClose())}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Restore “{company?.name}”?</DialogTitle>
          <DialogDescription>
            The company returns to active and its Company Admin, HR, Managers and employees can sign in
            again.
          </DialogDescription>
        </DialogHeader>
        <div className="flex justify-end gap-2 pt-1">
          <Button type="button" variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="button" disabled={mutation.isPending} onClick={() => mutation.mutate()}>
            {mutation.isPending ? 'Restoring…' : 'Restore company'}
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
