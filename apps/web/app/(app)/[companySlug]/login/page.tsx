'use client';

import { useParams } from 'next/navigation';
import { SluggedDoor } from '@/components/auth/slugged-door';
import { StaffLoginScreen } from '@/components/auth/staff-login-screen';

/**
 * Slugged STAFF sign-in `/{companySlug}/login` (§6, audience-specific). UNAUTHENTICATED — the `[companySlug]`
 * tenancy guard carves this sub-path out. {@link SluggedDoor} 404s a non-existent/archived slug and supplies
 * the company name; the SHARED {@link StaffLoginScreen} renders with `audience="STAFF"` (the API refuses a
 * credentialed employee here and points them to their workspace door).
 */
export default function SluggedStaffLoginPage() {
  const slug = String(useParams().companySlug ?? '');
  return (
    <SluggedDoor slug={slug}>
      {(companyName) => <StaffLoginScreen slug={slug} companyName={companyName} audience="STAFF" />}
    </SluggedDoor>
  );
}
