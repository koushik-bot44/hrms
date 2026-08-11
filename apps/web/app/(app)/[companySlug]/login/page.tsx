import { redirect } from 'next/navigation';

/**
 * Legacy slugged STAFF door `/{companySlug}/login` (§6). The sign-in doors consolidated to two TOP-LEVEL doors
 * (the slug is applied only after sign-in), so this now permanently REDIRECTS to the top-level staff door —
 * keeping links already sitting in inboxes working. Any `?email=` is preserved. Server-side redirect, so it
 * fires before the tenancy guard and never flashes.
 */
export default function SluggedStaffLoginPage({
  searchParams,
}: {
  searchParams: { email?: string | string[] };
}) {
  const email = typeof searchParams.email === 'string' ? searchParams.email : undefined;
  redirect(email ? `/login?email=${encodeURIComponent(email)}` : '/login');
}
