'use client';

import { useQuery } from '@tanstack/react-query';
import { getHealth } from '@/lib/api/health';

export type ApiStatus = 'loading' | 'online' | 'degraded' | 'offline';

/**
 * Polls GET /health and maps it to a coarse status:
 *   online   — API reachable and db: up
 *   degraded — API reachable but db: down
 *   offline  — API unreachable / error
 */
export function useApiStatus() {
  const query = useQuery({
    queryKey: ['health'],
    queryFn: ({ signal }) => getHealth(signal),
    refetchInterval: 30_000,
    retry: 1,
  });

  let status: ApiStatus = 'loading';
  if (query.isLoading) {
    status = 'loading';
  } else if (query.isError) {
    status = 'offline';
  } else if (query.data) {
    status = query.data.db === 'up' ? 'online' : 'degraded';
  }

  return { status, query };
}
