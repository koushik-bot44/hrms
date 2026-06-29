'use client';

import * as React from 'react';
import { useRouter } from 'next/navigation';
import { useQueryClient } from '@tanstack/react-query';
import { Trash2 } from 'lucide-react';
import { deleteTeam } from '@/lib/api/teams';
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

export function DeleteTeamDialog({ teamId, teamName }: { teamId: string; teamName: string }) {
  const [open, setOpen] = React.useState(false);
  const router = useRouter();
  const queryClient = useQueryClient();

  const mutation = useApiMutation(() => deleteTeam(teamId), {
    successMessage: `Team “${teamName}” deleted`,
    onSuccess: () => {
      setOpen(false);
      void queryClient.invalidateQueries({ queryKey: ['teams'] });
      router.replace('/company-admin');
    },
  });

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button variant="outline" size="sm" className="text-destructive hover:text-destructive">
          <Trash2 />
          Delete
        </Button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Delete this team?</DialogTitle>
          <DialogDescription>
            “{teamName}” will be removed and its HR and Manager unassigned. This cannot be undone.
          </DialogDescription>
        </DialogHeader>
        <div className="flex justify-end gap-2 pt-2">
          <Button variant="ghost" onClick={() => setOpen(false)}>
            Cancel
          </Button>
          <Button
            variant="destructive"
            disabled={mutation.isPending}
            onClick={() => mutation.mutate()}
          >
            {mutation.isPending ? 'Deleting…' : 'Delete team'}
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
