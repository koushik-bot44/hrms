'use client';

import { useQueryClient } from '@tanstack/react-query';
import { AlertTriangle, Trash2, UserMinus } from 'lucide-react';
import {
  deletePerson,
  editPerson,
  getDeletePreflight,
  iclockKeys,
  type IclockPerson,
} from '@/lib/api/iclock';
import { useApiMutation, useApiQuery } from '@/lib/api/hooks';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { LoadingSkeleton } from '@/components/loading-skeleton';
import { surface } from '@/components/ui/surface';
import { cn } from '@/lib/utils';

/**
 * Deleting a roster person — guarded, and honest about why when it refuses.
 *
 * The guard is the point. Someone with attendance punches cannot be hard-deleted: the foreign key
 * refuses it anyway, so without a preflight the operator's reward for trying is a 500 they cannot
 * interpret. And their actual intent is almost always "this person has left", whose correct expression
 * is DEACTIVATE — history stays attributable, the pin stops resolving. So the refusal offers that
 * instead of just saying no.
 *
 * The preflight runs when the dialog opens, not after the click, so the consequence is on screen
 * before the decision rather than after it.
 */
export function DeletePersonDialog({
  person,
  open,
  onOpenChange,
}: {
  person: IclockPerson | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const qc = useQueryClient();
  const personId = person?.id ?? '';

  const preflight = useApiQuery(
    iclockKeys.deletePreflight(personId),
    (signal) => getDeletePreflight(personId, signal),
    { enabled: open && Boolean(personId), retry: false },
  );

  const invalidate = () => {
    if (person) qc.invalidateQueries({ queryKey: iclockKeys.site(person.siteId) });
  };

  const remove = useApiMutation(() => deletePerson(personId), {
    successMessage: (r) => `${r.name ?? `Pin ${r.pin}`} removed from the roster.`,
    onSuccess: () => {
      invalidate();
      onOpenChange(false);
    },
  });

  const deactivate = useApiMutation(
    () => editPerson(personId, { pin: person?.pin ?? '', active: false }),
    {
      successMessage: 'Deactivated — their history stays, their pin stops resolving.',
      onSuccess: () => {
        invalidate();
        onOpenChange(false);
      },
    },
  );

  const label = person?.name ?? `Pin ${person?.pin ?? ''}`;
  const info = preflight.data;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>Remove {label} from the roster?</DialogTitle>
          <DialogDescription>
            This is a permanent removal, not a status change.
          </DialogDescription>
        </DialogHeader>

        {preflight.isLoading ? (
          <LoadingSkeleton lines={3} />
        ) : info ? (
          <div className="space-y-4">
            <div
              className={cn(
                surface('subtle'),
                'p-4 text-sm',
                info.allowed ? undefined : 'border-warning/30 bg-warning/10',
              )}
            >
              {!info.allowed ? (
                <AlertTriangle className="mb-2 size-4 text-warning" aria-hidden />
              ) : null}
              <p className={info.allowed ? 'text-muted-foreground' : 'text-foreground'}>
                {info.reason}
              </p>
            </div>

            {info.allowed ? (
              <p className="text-sm text-muted-foreground">
                Their pin becomes unmapped. Any future punch on it arrives in the inbox as an unknown
                pin, rather than being attributed to nobody in silence.
              </p>
            ) : null}

            <div className="flex flex-wrap justify-end gap-2 border-t border-border pt-4">
              <Button variant="ghost" onClick={() => onOpenChange(false)}>
                Cancel
              </Button>
              {!info.allowed && person?.active ? (
                <Button
                  variant="outline"
                  onClick={() => deactivate.mutate()}
                  disabled={deactivate.isPending}
                >
                  <UserMinus className="size-4" />
                  {deactivate.isPending ? 'Deactivating…' : 'Deactivate instead'}
                </Button>
              ) : null}
              <Button
                variant="destructive"
                onClick={() => remove.mutate()}
                disabled={!info.allowed || remove.isPending}
              >
                <Trash2 className="size-4" />
                {remove.isPending ? 'Removing…' : 'Remove permanently'}
              </Button>
            </div>
          </div>
        ) : null}
      </DialogContent>
    </Dialog>
  );
}
