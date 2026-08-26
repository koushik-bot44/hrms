'use client';

import * as React from 'react';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { listSites, iclockKeys, type IclockSite } from '@/lib/api/iclock';
import { useApiQuery } from '@/lib/api/hooks';
import type { ApiError } from '@/lib/api/client';

/**
 * The console's site scope, carried in the URL as `?site=`.
 *
 * A site is NOT a tenant: V43 links many companies to one site, and SUPER_ADMIN has no `companySlug`
 * at all — `RequireCompany` bounces every PLATFORM role off `/[companySlug]/**`. So the scope cannot
 * live in the path the way a company does. The query string keeps every console screen linkable and
 * survives a refresh, which matters when an operator is sending someone a link to a specific gate.
 *
 * Selection auto-settles on the first site when the URL names none (or names one that no longer
 * exists), via `router.replace` so the fixup never adds a history entry the back button can trip on.
 */
export interface SiteScope {
  siteId: string | null;
  sites: IclockSite[];
  site: IclockSite | null;
  setSiteId: (siteId: string) => void;
  isLoading: boolean;
  isError: boolean;
  /** Carried through so the caller can tell an authorization failure from a real outage. */
  error: ApiError | null;
  /** No sites exist at all — a different empty state from "still loading". */
  isEmpty: boolean;
}

export function useSiteScope(): SiteScope {
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const requested = params.get('site');

  const query = useApiQuery(iclockKeys.sites(), listSites, { retry: false });
  const sites = React.useMemo(() => query.data ?? [], [query.data]);

  const resolved = React.useMemo(() => {
    if (requested && sites.some((s) => s.id === requested)) return requested;
    return sites[0]?.id ?? null;
  }, [requested, sites]);

  const setSiteId = React.useCallback(
    (siteId: string) => {
      const next = new URLSearchParams(params.toString());
      next.set('site', siteId);
      router.replace(`${pathname}?${next.toString()}`);
    },
    [params, pathname, router],
  );

  // Pin the resolved site into the URL when it was absent or stale, so every screen the operator
  // navigates to next inherits the same scope instead of silently re-defaulting.
  React.useEffect(() => {
    if (resolved && requested !== resolved) {
      const next = new URLSearchParams(params.toString());
      next.set('site', resolved);
      router.replace(`${pathname}?${next.toString()}`);
    }
  }, [resolved, requested, params, pathname, router]);

  return {
    siteId: resolved,
    sites,
    site: sites.find((s) => s.id === resolved) ?? null,
    setSiteId,
    isLoading: query.isLoading,
    isError: query.isError,
    error: query.error ?? null,
    isEmpty: !query.isLoading && !query.isError && sites.length === 0,
  };
}
