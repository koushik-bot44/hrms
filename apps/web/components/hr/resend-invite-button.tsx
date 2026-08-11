'use client';

import * as React from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Send } from 'lucide-react';
import { resendInvite } from '@/lib/api/employees';
import { useApiMutation } from '@/lib/api/hooks';
import { relativeTime } from '@/lib/date';
import { Button } from '@/components/ui/button';

/**
 * HR "Resend invite" for an INVITED employee (§3.2/§6). Issues a fresh link (revoking the previous one) and
 * re-emails it, then refreshes the record so the "invite sent" time updates. Shown only while INVITED.
 */
export function ResendInviteButton({
  employeeId,
  sentAt,
}: {
  employeeId: string;
  sentAt: string | null;
}) {
  const queryClient = useQueryClient();
  const mutation = useApiMutation(() => resendInvite(employeeId), {
    successMessage: 'Invitation resent — the previous link no longer works.',
    errorMessage: 'Could not resend the invitation',
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['hr-record', employeeId] });
    },
  });

  return (
    <div className="flex flex-col items-end gap-0.5">
      <Button
        type="button"
        variant="outline"
        size="sm"
        onClick={() => mutation.mutate()}
        disabled={mutation.isPending}
      >
        <Send className="size-4" />
        {mutation.isPending ? 'Sending…' : 'Resend invite'}
      </Button>
      {sentAt ? (
        <span className="text-[11px] text-muted-foreground">Invite sent {relativeTime(sentAt)}</span>
      ) : null}
    </div>
  );
}
