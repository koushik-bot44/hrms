'use client';

import { useParams } from 'next/navigation';
import { SluggedDoor } from '@/components/auth/slugged-door';
import { StaffLoginScreen } from '@/components/auth/staff-login-screen';

/**
 * Slugged staff sign-in `/{companySlug}/login` (Stage 3). UNAUTHENTICATED — the `[companySlug]` tenancy guard
 * carves this sub-path out. {@link SluggedDoor} 404s a non-existent/archived slug and supplies the company
 * name; the SHARED {@link StaffLoginScreen} renders (same component as the top-level door).
 */
export default function SluggedStaffLoginPage() {
  const slug = String(useParams().companySlug ?? '');
  return (
    <SluggedDoor slug={slug}>
      {(companyName) => <StaffLoginScreen slug={slug} companyName={companyName} />}
    </SluggedDoor>
  );
}
