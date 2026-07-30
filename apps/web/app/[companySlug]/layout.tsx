'use client';

import type { ReactNode } from 'react';
import { RequireCompany } from '@/components/require-company';

/**
 * The tenant boundary (Stage 2). Everything under `/[companySlug]/…` is a company-scoped area; this
 * layout runs the {@link RequireCompany} tenancy guard (slug ↔ session's company) around all of them.
 * Role is still enforced by each area's own RequireRole layout — the two compose.
 */
export default function CompanySlugLayout({ children }: { children: ReactNode }) {
  return <RequireCompany>{children}</RequireCompany>;
}
