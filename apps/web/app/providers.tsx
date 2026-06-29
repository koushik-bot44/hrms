'use client';

import * as React from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { makeQueryClient } from '@/lib/api/query-client';
import { AuthProvider } from '@/components/auth-provider';

/** Client-side providers mounted once at the root. */
export function Providers({ children }: { children: React.ReactNode }) {
  // Lazily create a single client per browser session (not per render).
  const [queryClient] = React.useState(() => makeQueryClient());

  return (
    <QueryClientProvider client={queryClient}>
      <AuthProvider>{children}</AuthProvider>
    </QueryClientProvider>
  );
}
