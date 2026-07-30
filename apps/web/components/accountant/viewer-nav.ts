'use client';

import { useParams, usePathname } from 'next/navigation';

/**
 * The base path of the CURRENT read-only viewer mount (§2/§6): the platform ACCOUNTS_ADMIN mount
 * (`/accounts`) or the team ACCOUNTANT mount (`/{companySlug}/accountant`). The shared viewer components
 * build their internal links (`${base}/employees/{id}`, the `${base}` overview) from this, so one
 * component works under both mounts without duplicating screen code.
 */
export function useViewerBase(): string {
  const pathname = usePathname();
  const params = useParams();
  if (pathname === '/accounts' || pathname.startsWith('/accounts/')) {
    return '/accounts';
  }
  return `/${String(params.companySlug ?? '')}/accountant`;
}
