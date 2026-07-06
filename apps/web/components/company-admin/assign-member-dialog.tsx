'use client';

import * as React from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useQueryClient } from '@tanstack/react-query';
import { UserPlus } from 'lucide-react';
import {
  AssignNewMemberSchema,
  type AssignMemberInput,
  type AssignNewMemberInput,
  type TeamRole,
} from '@/lib/contract';
import { assignTeamMember, getAssignableUsers } from '@/lib/api/teams';
import { useApiMutation, useApiQuery } from '@/lib/api/hooks';
import { generatePassword } from '@/lib/auth/password';
import { CredentialNotice } from '@/components/staff-credential-notice';
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
  const [created, setCreated] = React.useState<{ email: string; password: string } | null>(null);
  const queryClient = useQueryClient();

  const newForm = useForm<AssignNewMemberInput>({
    resolver: zodResolver(AssignNewMemberSchema),
    defaultValues: { name: '', email: '', password: '' },
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
      onSuccess: (_result, variables) => {
        void queryClient.invalidateQueries({ queryKey: ['team', teamId] });
        void queryClient.invalidateQueries({ queryKey: ['teams'] });
        void queryClient.invalidateQueries({ queryKey: ['assignable', role] });
        setUserId('');
        // A newly-created person has an initial password to hand over; keep the dialog open to show
        // it. Attaching an existing user just closes.
        if ('password' in variables && variables.password) {
          setCreated({ email: variables.email, password: variables.password });
          newForm.reset();
        } else {
          setOpen(false);
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
          setCreated(null);
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
            {created ? (
              <div className="space-y-4">
                <CredentialNotice
                  title={`${roleLabel} assigned`}
                  email={created.email}
                  password={created.password}
                  onDismiss={() => setCreated(null)}
                />
                <div className="flex justify-end gap-2">
                  <Button type="button" variant="outline" onClick={() => setCreated(null)}>
                    Assign another
                  </Button>
                  <Button type="button" onClick={() => setOpen(false)}>
                    Done
                  </Button>
                </div>
              </div>
            ) : (
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
                </div>
                <div className="space-y-1.5">
                  <div className="flex items-center justify-between">
                    <label htmlFor="member-password" className="text-sm font-medium">
                      Initial password
                    </label>
                    <Button
                      type="button"
                      variant="ghost"
                      size="sm"
                      onClick={() =>
                        newForm.setValue('password', generatePassword(), { shouldValidate: true })
                      }
                    >
                      Generate
                    </Button>
                  </div>
                  <Input
                    id="member-password"
                    type="text"
                    autoComplete="off"
                    placeholder="At least 8 characters"
                    aria-invalid={Boolean(newForm.formState.errors.password)}
                    {...newForm.register('password')}
                  />
                  {newForm.formState.errors.password ? (
                    <p className="text-xs text-destructive">
                      {newForm.formState.errors.password.message}
                    </p>
                  ) : (
                    <p className="text-xs text-muted-foreground">
                      They sign in with their email + this password and can change it later.
                    </p>
                  )}
                </div>
                <div className="flex justify-end pt-1">
                  <Button type="submit" disabled={mutation.isPending}>
                    {mutation.isPending ? 'Assigning…' : `Assign ${roleLabel}`}
                  </Button>
                </div>
              </form>
            )}
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
