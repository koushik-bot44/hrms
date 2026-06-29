'use client';

import * as React from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { UserPlus } from 'lucide-react';
import {
  AssignNewMemberSchema,
  type AssignMemberInput,
  type AssignNewMemberInput,
  type TeamRole,
} from '@ihrms/shared';
import { assignTeamMember, getAssignableUsers } from '@/lib/api/teams';
import { useApiMutation, useApiQuery } from '@/lib/api/hooks';
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
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';

const SELECT_CLASS =
  'flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50';

export function AssignMemberDialog({
  teamId,
  role,
  label,
}: {
  teamId: string;
  role: TeamRole;
  label: string;
}) {
  const roleLabel = role === 'HR' ? 'HR' : 'Manager';
  const [open, setOpen] = React.useState(false);
  const [mode, setMode] = React.useState<'new' | 'existing'>('new');
  const [userId, setUserId] = React.useState('');
  const [existingError, setExistingError] = React.useState<string | undefined>(undefined);
  const queryClient = useQueryClient();

  const newForm = useForm<AssignNewMemberInput>({
    resolver: zodResolver(AssignNewMemberSchema),
    defaultValues: { name: '', email: '' },
  });

  const assignable = useApiQuery(
    ['assignable', role],
    (signal) => getAssignableUsers(role, signal),
    { enabled: open && mode === 'existing' },
  );

  const mutation = useApiMutation(
    (body: AssignMemberInput) => assignTeamMember(teamId, role, body),
    {
      successMessage: `${roleLabel} assigned`,
      onSuccess: (result) => {
        setOpen(false);
        newForm.reset();
        setUserId('');
        void queryClient.invalidateQueries({ queryKey: ['team', teamId] });
        void queryClient.invalidateQueries({ queryKey: ['teams'] });
        void queryClient.invalidateQueries({ queryKey: ['assignable', role] });
        if (result.devPassword) {
          const email = role === 'HR' ? result.team.hr?.email : result.team.manager?.email;
          toast.message('Temporary password (dev only)', {
            description: `${email ?? ''} · ${result.devPassword}`,
          });
        }
      },
      onError: (error) => {
        if (mode === 'new' && error.status === 409) {
          newForm.setError('email', { message: error.message });
        }
      },
    },
  );

  const submitNew = newForm.handleSubmit((values) => mutation.mutate(values));
  const submitExisting = (event: React.FormEvent) => {
    event.preventDefault();
    if (!userId) {
      setExistingError('Select a person');
      return;
    }
    mutation.mutate({ userId });
  };

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        if (!next) {
          newForm.reset();
          setUserId('');
          setExistingError(undefined);
        }
      }}
    >
      <DialogTrigger asChild>
        <Button size="sm" variant="outline">
          <UserPlus />
          {label}
        </Button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Assign {roleLabel}</DialogTitle>
          <DialogDescription>
            Create a new {roleLabel} or pick an unassigned one from your company.
          </DialogDescription>
        </DialogHeader>

        <Tabs value={mode} onValueChange={(value) => setMode(value as 'new' | 'existing')}>
          <TabsList className="grid w-full grid-cols-2">
            <TabsTrigger value="new">New person</TabsTrigger>
            <TabsTrigger value="existing">Existing</TabsTrigger>
          </TabsList>

          <TabsContent value="new">
            <form onSubmit={submitNew} className="space-y-4" noValidate>
              <div className="space-y-1.5">
                <label htmlFor="member-name" className="text-sm font-medium">
                  Name
                </label>
                <Input
                  id="member-name"
                  placeholder="Jordan Lee"
                  aria-invalid={Boolean(newForm.formState.errors.name)}
                  {...newForm.register('name')}
                />
                {newForm.formState.errors.name ? (
                  <p className="text-xs text-destructive">{newForm.formState.errors.name.message}</p>
                ) : null}
              </div>
              <div className="space-y-1.5">
                <label htmlFor="member-email" className="text-sm font-medium">
                  Email
                </label>
                <Input
                  id="member-email"
                  type="email"
                  placeholder={`${role.toLowerCase()}@company.com`}
                  aria-invalid={Boolean(newForm.formState.errors.email)}
                  {...newForm.register('email')}
                />
                {newForm.formState.errors.email ? (
                  <p className="text-xs text-destructive">
                    {newForm.formState.errors.email.message}
                  </p>
                ) : null}
                <p className="text-xs text-muted-foreground">
                  Initial credentials are emailed (logged to the server in dev).
                </p>
              </div>
              <div className="flex justify-end pt-1">
                <Button type="submit" disabled={mutation.isPending}>
                  {mutation.isPending ? 'Assigning…' : `Assign ${roleLabel}`}
                </Button>
              </div>
            </form>
          </TabsContent>

          <TabsContent value="existing">
            <form onSubmit={submitExisting} className="space-y-4">
              <div className="space-y-1.5">
                <label htmlFor="member-existing" className="text-sm font-medium">
                  Unassigned {roleLabel}s
                </label>
                {assignable.isLoading ? (
                  <p className="text-sm text-muted-foreground">Loading…</p>
                ) : assignable.data && assignable.data.length > 0 ? (
                  <select
                    id="member-existing"
                    className={SELECT_CLASS}
                    value={userId}
                    onChange={(event) => {
                      setUserId(event.target.value);
                      setExistingError(undefined);
                    }}
                  >
                    <option value="">Select a person…</option>
                    {assignable.data.map((user) => (
                      <option key={user.id} value={user.id}>
                        {user.name} · {user.email}
                      </option>
                    ))}
                  </select>
                ) : (
                  <p className="text-sm text-muted-foreground">
                    No unassigned {roleLabel}s. Create a new one instead.
                  </p>
                )}
                {existingError ? <p className="text-xs text-destructive">{existingError}</p> : null}
              </div>
              <div className="flex justify-end pt-1">
                <Button
                  type="submit"
                  disabled={mutation.isPending || !assignable.data || assignable.data.length === 0}
                >
                  {mutation.isPending ? 'Assigning…' : `Assign ${roleLabel}`}
                </Button>
              </div>
            </form>
          </TabsContent>
        </Tabs>
      </DialogContent>
    </Dialog>
  );
}
