'use client';

import * as React from 'react';
import { useParams } from 'next/navigation';
import { buildCompanyPath } from '@/lib/company-url';

/**
 * Build slugged links from INSIDE the `/[companySlug]/…` tree (Stage 2). Reads the current route's
 * `companySlug` param — the tenancy guard has already validated it equals the session's own slug — so
 * `cp('/hr/employees')` → `/{slug}/hr/employees`. The single builder used at every in-tree call site.
 */
export function useCompanyPath(): (subpath?: string) => string {
  const params = useParams();
  const slug = String(params.companySlug ?? '');
  return React.useCallback((subpath = '') => buildCompanyPath(slug, subpath), [slug]);
}
