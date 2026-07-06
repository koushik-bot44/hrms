'use client';

import * as React from 'react';
import { toast } from 'sonner';
import { Check, Copy, X } from 'lucide-react';
import { Button } from '@/components/ui/button';

/**
 * Shows the initial staff credentials once, on successful provisioning, so the admin can share them.
 * The password is only known at creation time — surface it clearly with a copy affordance.
 */
export function CredentialNotice({
  title,
  email,
  password,
  onDismiss,
}: {
  title: string;
  email: string;
  password: string;
  onDismiss: () => void;
}) {
  const [copied, setCopied] = React.useState(false);

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(`${email} / ${password}`);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      toast.error('Could not copy to clipboard');
    }
  };

  return (
    <div className="rounded-md border border-success/30 bg-success/5 p-3 text-sm">
      <div className="flex items-start justify-between gap-2">
        <p className="font-medium">{title}</p>
        <button
          type="button"
          onClick={onDismiss}
          aria-label="Dismiss"
          className="text-muted-foreground hover:text-foreground"
        >
          <X className="size-4" />
        </button>
      </div>
      <p className="mt-1 text-muted-foreground">
        Share these sign-in credentials — the password is shown only now.
      </p>
      <div className="mt-2 flex items-center justify-between gap-3 rounded bg-background px-2.5 py-1.5 font-mono text-xs">
        <span className="truncate">
          {email} · {password}
        </span>
        <Button type="button" variant="ghost" size="sm" onClick={copy}>
          {copied ? <Check className="size-3.5" /> : <Copy className="size-3.5" />}
          {copied ? 'Copied' : 'Copy'}
        </Button>
      </div>
    </div>
  );
}
