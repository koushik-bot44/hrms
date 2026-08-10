'use client';

import { useParams } from 'next/navigation';
import { SluggedDoor } from '@/components/auth/slugged-door';
import { EmployeeLoginScreen } from '@/components/auth/employee-login-screen';

/**
 * Slugged employee sign-in `/{companySlug}/employee/login` (Stage 3) — where the HR invite link now lands
 * (`?email=…` prefill preserved). UNAUTHENTICATED — the `[companySlug]` tenancy guard carves this sub-path out.
 * {@link SluggedDoor} 404s a non-existent/archived slug and supplies the company name; the SHARED
 * {@link EmployeeLoginScreen} renders (same component as the top-level door).
 */
export default function SluggedEmployeeLoginPage() {
  const slug = String(useParams().companySlug ?? '');
  return (
    <SluggedDoor slug={slug}>
      {(companyName) => <EmployeeLoginScreen slug={slug} companyName={companyName} />}
    </SluggedDoor>
  );
}
