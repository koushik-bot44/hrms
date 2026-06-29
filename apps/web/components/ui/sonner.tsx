'use client';

import { Toaster as SonnerToaster } from 'sonner';

/** Themed app toaster — wraps sonner with project defaults. */
export function Toaster() {
  return (
    <SonnerToaster
      position="top-right"
      toastOptions={{
        classNames: {
          toast:
            'group rounded-md border border-border bg-popover text-popover-foreground shadow-card',
          description: 'text-muted-foreground',
          actionButton: 'bg-primary text-primary-foreground',
        },
      }}
    />
  );
}
