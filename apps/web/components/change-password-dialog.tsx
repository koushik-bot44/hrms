'use client';

import * as React from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { toast } from 'sonner';
import { ChangePasswordSchema, type ChangePasswordInput } from '@/lib/contract';
import { useAuth } from '@/components/auth-provider';
import { ApiError } from '@/lib/api/client';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';

/** Self-service password change for a signed-in staff member (§6). */
export function ChangePasswordDialog({
  open,
  onOpenChange,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const auth = useAuth();
  const {
    register,
    handleSubmit,
    reset,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<ChangePasswordInput>({
    resolver: zodResolver(ChangePasswordSchema),
    defaultValues: { currentPassword: '', newPassword: '' },
  });

  const close = (next: boolean) => {
    onOpenChange(next);
    if (!next) reset();
  };

  const onSubmit = handleSubmit(async (values) => {
    try {
      await auth.changePassword(values);
      toast.success('Password changed');
      close(false);
    } catch (error) {
      const message =
        error instanceof ApiError && error.message ? error.message : 'Could not change password';
      // A 400 is the current-password check failing.
      if (error instanceof ApiError && error.status === 400) {
        setError('currentPassword', { message });
      } else {
        toast.error(message);
      }
    }
  });

  return (
    <Dialog open={open} onOpenChange={close}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Change password</DialogTitle>
          <DialogDescription>
            Enter your current password and choose a new one (at least 8 characters).
          </DialogDescription>
        </DialogHeader>
        <form onSubmit={onSubmit} className="space-y-4" noValidate>
          <div className="space-y-1.5">
            <label htmlFor="cp-current" className="text-sm font-medium">
              Current password
            </label>
            <Input
              id="cp-current"
              type="password"
              autoComplete="current-password"
              aria-invalid={Boolean(errors.currentPassword)}
              {...register('currentPassword')}
            />
            {errors.currentPassword ? (
              <p className="text-xs text-destructive">{errors.currentPassword.message}</p>
            ) : null}
          </div>
          <div className="space-y-1.5">
            <label htmlFor="cp-new" className="text-sm font-medium">
              New password
            </label>
            <Input
              id="cp-new"
              type="password"
              autoComplete="new-password"
              aria-invalid={Boolean(errors.newPassword)}
              {...register('newPassword')}
            />
            {errors.newPassword ? (
              <p className="text-xs text-destructive">{errors.newPassword.message}</p>
            ) : null}
          </div>
          <div className="flex justify-end gap-2 pt-1">
            <Button type="button" variant="ghost" onClick={() => close(false)}>
              Cancel
            </Button>
            <Button type="submit" disabled={isSubmitting}>
              {isSubmitting ? 'Saving…' : 'Change password'}
            </Button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}
