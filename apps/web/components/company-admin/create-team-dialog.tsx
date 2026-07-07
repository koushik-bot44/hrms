'use client';

import * as React from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useQueryClient } from '@tanstack/react-query';
import { Plus } from 'lucide-react';
import { CreateTeamSchema, type CreateTeamInput, type TeamSummary } from '@/lib/contract';
import { createTeam, teamsKey } from '@/lib/api/teams';
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
import { Input } from '@/components/ui/input';

interface OptimisticContext {
  prev?: TeamSummary[];
}

/** COMPANY_ADMIN creates in its own company; SUPER_ADMIN passes a `companyId` to create in any. */
export function CreateTeamDialog({ companyId }: { companyId?: string }) {
  const [open, setOpen] = React.useState(false);
  const queryClient = useQueryClient();
  const TEAMS_KEY = teamsKey(companyId);

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<CreateTeamInput>({
    resolver: zodResolver(CreateTeamSchema),
    defaultValues: { name: '' },
  });

  const mutation = useApiMutation((body: CreateTeamInput) => createTeam(body, companyId), {
    successMessage: (team) => `Team “${team.name}” created`,
    onMutate: async (vars): Promise<OptimisticContext> => {
      await queryClient.cancelQueries({ queryKey: TEAMS_KEY });
      const prev = queryClient.getQueryData<TeamSummary[]>(TEAMS_KEY);
      const optimistic: TeamSummary = {
        id: `optimistic-${vars.name}`,
        name: vars.name,
        hr: null,
        manager: null,
        accountant: null,
        complete: false,
        memberCount: 0,
        createdAt: new Date().toISOString(),
      };
      queryClient.setQueryData<TeamSummary[]>(TEAMS_KEY, (old) =>
        old ? [optimistic, ...old] : [optimistic],
      );
      return { prev };
    },
    onError: (_error, _vars, context) => {
      const ctx = context as OptimisticContext | undefined;
      if (ctx?.prev) {
        queryClient.setQueryData(TEAMS_KEY, ctx.prev);
      }
    },
    onSuccess: () => {
      setOpen(false);
      reset();
    },
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: TEAMS_KEY });
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
        <Button size="sm">
          <Plus />
          New team
        </Button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Create a team</DialogTitle>
          <DialogDescription>
            Give the team a name, then assign its HR and Manager.
          </DialogDescription>
        </DialogHeader>
        <form onSubmit={onSubmit} className="space-y-4" noValidate>
          <div className="space-y-1.5">
            <label htmlFor="team-name" className="text-sm font-medium">
              Team name
            </label>
            <Input
              id="team-name"
              placeholder="Engineering"
              aria-invalid={Boolean(errors.name)}
              {...register('name')}
            />
            {errors.name ? <p className="text-xs text-destructive">{errors.name.message}</p> : null}
          </div>
          <div className="flex justify-end gap-2 pt-2">
            <Button type="button" variant="ghost" onClick={() => setOpen(false)}>
              Cancel
            </Button>
            <Button type="submit" disabled={isSubmitting}>
              {isSubmitting ? 'Creating…' : 'Create team'}
            </Button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}
