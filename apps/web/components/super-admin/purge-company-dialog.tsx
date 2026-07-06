'use client';

import * as React from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { AlertTriangle } from 'lucide-react';
import type { CompanySummary } from '@/lib/contract';
import { purgeCompany } from '@/lib/api/companies';
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

/**
 * Type-the-name confirm to PERMANENTLY delete a company. Unlike archival this is irreversible — it
 * destroys the company and all of its data (staff, employees, forms, documents, audit).
 */
export function PurgeCompanyDialog({
  company,
  onClose,
}: {
  company: CompanySummary | null;
  onClose: () => void;
}) {
  const queryClient = useQueryClient();
  const [confirm, setConfirm] = React.useState('');

  React.useEffect(() => setConfirm(''), [company]);

  const mutation = useApiMutation(() => purgeCompany(company!.id), {
    successMessage: (r) => `“${r.name}” permanently deleted`,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['companies'] });
      void queryClient.invalidateQueries({ queryKey: ['companies', 'deleted'] });
      void queryClient.invalidateQueries({ queryKey: ['dashboard'] });
      onClose();
    },
  });

  const nameMatches =
    company != null && confirm.trim().toLowerCase() === company.name.trim().toLowerCase();

  return (
    <Dialog open={Boolean(company)} onOpenChange={(open) => (open ? null : onClose())}>
      <DialogContent>
        <DialogHeader>
          <div className="mb-1 flex size-9 items-center justify-center rounded-full bg-destructive/10 text-destructive">
            <AlertTriangle className="size-5" />
          </div>
          <DialogTitle>Permanently delete “{company?.name}”?</DialogTitle>
          <DialogDescription>
            This <strong>cannot be undone.</strong> It permanently erases the company and{' '}
            <strong>everything</strong> under it — its staff accounts, all employees, every onboarding
            form, uploaded document, signature, generated PDF, approvals, notifications, and its audit
            trail. Prefer <strong>Archive</strong> if you might ever need it back.
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
            <label htmlFor="purge-company" className="text-sm font-medium">
              Type <span className="font-semibold">{company?.name}</span> to confirm
            </label>
            <Input
              id="purge-company"
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
              {mutation.isPending ? 'Deleting…' : 'Permanently delete'}
            </Button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}
