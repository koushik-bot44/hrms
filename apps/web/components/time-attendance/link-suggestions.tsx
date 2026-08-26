'use client';

import { useQueryClient } from '@tanstack/react-query';
import { Link2, Link2Off, SearchX } from 'lucide-react';
import {
  confirmLink,
  getSuggestions,
  iclockKeys,
  unlinkPerson,
  type IclockPerson,
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
import { EmptyState } from '@/components/empty-state';
import { LoadingSkeleton } from '@/components/loading-skeleton';
import { surface } from '@/components/ui/surface';
import { cn } from '@/lib/utils';

/**
 * Links a roster person to their IHRMS employee record — deferred ENRICHMENT, never a precondition.
 *
 * Nothing is auto-linked, by design. HIGH means an exact email match and MEDIUM means a normalised
 * name match inside the person's own company; a confident-looking wrong link is worse than no link,
 * because it silently files one person's attendance under another's employment record. So the API
 * only ever suggests, and this dialog is where a human confirms.
 */
export function LinkSuggestionsDialog({
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

  const query = useApiQuery(
    iclockKeys.suggestions(personId),
    (signal) => getSuggestions(personId, signal),
    { enabled: open && Boolean(personId), retry: false },
  );

  const invalidate = () => {
    if (person) qc.invalidateQueries({ queryKey: iclockKeys.site(person.siteId) });
    qc.invalidateQueries({ queryKey: iclockKeys.suggestions(personId) });
  };

  const link = useApiMutation((employeeId: string) => confirmLink(personId, employeeId), {
    successMessage: 'Linked. Their existing punches were back-filled.',
    onSuccess: () => {
      invalidate();
      onOpenChange(false);
    },
  });

  const unlink = useApiMutation(() => unlinkPerson(personId), {
    successMessage: 'Unlinked.',
    onSuccess: () => {
      invalidate();
      onOpenChange(false);
    },
  });

  const label = person?.name ?? `Pin ${person?.pin ?? ''}`;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>Link {label} to an employee</DialogTitle>
          <DialogDescription>
            Attendance already works without this. Linking connects it to their IHRMS employment
            record and back-fills the punches they have already made.
          </DialogDescription>
        </DialogHeader>

        {person?.employeeId ? (
          <div className={cn(surface('subtle'), 'flex items-center justify-between gap-3 p-4')}>
            <div className="min-w-0">
              <div className="text-sm text-muted-foreground">Currently linked to</div>
              <div className="truncate font-medium">{person.employeeName ?? person.employeeId}</div>
            </div>
            <Button
              size="sm"
              variant="ghost"
              onClick={() => unlink.mutate()}
              disabled={unlink.isPending}
            >
              <Link2Off className="size-4" />
              Unlink
            </Button>
          </div>
        ) : query.isLoading ? (
          <LoadingSkeleton lines={3} />
        ) : query.data && query.data.length > 0 ? (
          <ul className="space-y-2">
            {query.data.map((s) => (
              <li
                key={s.employeeId}
                className={cn(surface('subtle'), 'flex items-center justify-between gap-3 p-3')}
              >
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="truncate font-medium">{s.employeeName ?? 'Unnamed'}</span>
                    <Badge variant={s.confidence === 'HIGH' ? 'success' : 'warning'}>
                      {s.confidence === 'HIGH' ? 'Email match' : 'Name match'}
                    </Badge>
                  </div>
                  <div className="truncate text-xs text-muted-foreground">
                    {s.employeeEmail ?? 'No email'} · {s.employeeStatus} · {s.basis}
                  </div>
                </div>
                <Button
                  size="sm"
                  onClick={() => link.mutate(s.employeeId)}
                  disabled={link.isPending}
                >
                  <Link2 className="size-4" />
                  Link
                </Button>
              </li>
            ))}
          </ul>
        ) : (
          <EmptyState
            icon={SearchX}
            title="No candidates"
            description="No IHRMS employee matches this person by email, or by name within their company. They may not be onboarded yet — attendance keeps working regardless."
            className="py-8"
          />
        )}
      </DialogContent>
    </Dialog>
  );
}
