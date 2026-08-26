'use client';

import { Lock, TriangleAlert } from 'lucide-react';
import type { ApiError } from '@/lib/api/client';
import { EmptyState } from '@/components/empty-state';

/**
 * Failure state for every console read.
 *
 * It branches on 403 **or 500**, not on 403 alone. `IclockAdminController` is guarded twice — the
 * filter chain (`/provisioning/**` → `hasRole('SUPER_ADMIN')`) and a belt-and-braces `@PreAuthorize` —
 * and the controller's own javadoc records that a `@PreAuthorize` denial surfaces as a **500** here,
 * because the global advice has a catch-all `Exception` handler and no `AccessDeniedException`
 * handler. Treating 500 as "something broke" would tell an unauthorised operator to file a bug.
 */
export function ConsoleError({ error }: { error: ApiError | null }) {
  const status = error?.status ?? 0;

  if (status === 403 || status === 500) {
    return (
      <EmptyState
        icon={Lock}
        title="You don't have access to this"
        description="Time & Attendance is restricted to platform super admins. If you believe you should have access, ask whoever manages the platform."
      />
    );
  }

  if (status === 0) {
    return (
      <EmptyState
        icon={TriangleAlert}
        title="Can't reach the server"
        description="The console could not contact the API. Check the connection and try again."
      />
    );
  }

  return (
    <EmptyState
      icon={TriangleAlert}
      title="Couldn't load this"
      description={error?.message ?? 'The API returned an unexpected response.'}
    />
  );
}
