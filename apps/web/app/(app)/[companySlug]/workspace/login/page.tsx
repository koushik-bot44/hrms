'use client';

import { useParams } from 'next/navigation';
import { SluggedDoor } from '@/components/auth/slugged-door';
import { StaffLoginScreen } from '@/components/auth/staff-login-screen';

/**
 * Slugged WORKSPACE sign-in `/{companySlug}/workspace/login` (§6) — where an APPROVED, credentialed employee
 * signs in with their workspace credentials (the credential email links here). UNAUTHENTICATED: the
 * `[companySlug]` tenancy guard AND the workspace `RequireRole` both carve this sub-path out. {@link SluggedDoor}
 * 404s a bad/archived slug; the SHARED {@link StaffLoginScreen} renders with `audience="WORKSPACE"` (the API
 * refuses a staff user here and points them to the staff door).
 */
export default function SluggedWorkspaceLoginPage() {
  const slug = String(useParams().companySlug ?? '');
  return (
    <SluggedDoor slug={slug}>
      {(companyName) => <StaffLoginScreen slug={slug} companyName={companyName} audience="WORKSPACE" />}
    </SluggedDoor>
  );
}
