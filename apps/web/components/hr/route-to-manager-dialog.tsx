'use client';

import * as React from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Send } from 'lucide-react';
import {
  RouteToManagerSchema,
  type RouteToManagerInput,
  type RouteToManagerResult,
} from '@/lib/contract';
import { routeToManager } from '@/lib/api/review';
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

interface Props {
  employeeId: string;
  disabled: boolean;
  onRouted: (result: RouteToManagerResult) => void;
}

/** Confirm dialog for routing a fully-verified record to the team's Manager (§3.3). */
export function RouteToManagerDialog({ employeeId, disabled, onRouted }: Props) {
  const [open, setOpen] = React.useState(false);
  const {
    register,
    handleSubmit,
    reset,
    formState: { isSubmitting },
  } = useForm<RouteToManagerInput>({
    resolver: zodResolver(RouteToManagerSchema),
    defaultValues: { note: '' },
  });

  const mutation = useApiMutation((body: RouteToManagerInput) => routeToManager(employeeId, body), {
    successMessage: (result) =>
      result.managerName
        ? `Routed to ${result.managerName} for approval`
        : 'Routed to the Manager for approval',
    onSuccess: (result) => {
      setOpen(false);
      reset();
      onRouted(result);
    },
  });

  const onSubmit = handleSubmit((values) => mutation.mutate(values));

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        if (!next) reset();
      }}
    >
      <DialogTrigger asChild>
        <Button size="sm" disabled={disabled}>
          <Send />
          Route to Manager
        </Button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Route for approval</DialogTitle>
          <DialogDescription>
            Send this verified record to the team&apos;s Manager. The employee&apos;s record locks
            once routed.
          </DialogDescription>
        </DialogHeader>
        <form onSubmit={onSubmit} className="space-y-4" noValidate>
          <div className="space-y-1.5">
            <label htmlFor="route-note" className="text-sm font-medium">
              Note for the Manager <span className="text-muted-foreground">(optional)</span>
            </label>
            <textarea
              id="route-note"
              rows={3}
              placeholder="Anything the Manager should know…"
              className="flex w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
              {...register('note')}
            />
          </div>
          <div className="flex justify-end gap-2 pt-2">
            <Button type="button" variant="ghost" onClick={() => setOpen(false)}>
              Cancel
            </Button>
            <Button type="submit" disabled={isSubmitting}>
              {isSubmitting ? 'Routing…' : 'Confirm & route'}
            </Button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}
