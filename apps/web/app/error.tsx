'use client';

import { useEffect } from 'react';
import { Button } from '@/components/ui/button';

export default function Error({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    // Surfaced to monitoring in a later phase.
    console.error(error);
  }, [error]);

  return (
    <div className="container flex min-h-[60vh] flex-col items-center justify-center gap-4 text-center">
      <h1 className="text-2xl font-medium">Something went wrong</h1>
      <p className="max-w-md text-sm text-muted-foreground">
        An unexpected error occurred. You can try again — if it persists, the service may be
        temporarily unavailable.
      </p>
      <Button onClick={reset}>Try again</Button>
    </div>
  );
}
