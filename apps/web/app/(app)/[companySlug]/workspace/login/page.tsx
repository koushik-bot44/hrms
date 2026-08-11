import { redirect } from 'next/navigation';

/**
 * Legacy slugged WORKSPACE door `/{companySlug}/workspace/login` (§6). Consolidated to the top-level workspace
 * door (`/employee/login`), so this now permanently REDIRECTS there — keeping credential-email links already in
 * inboxes working. Any `?email=` is preserved. Server-side redirect (fires before the tenancy + role guards).
 */
export default function SluggedWorkspaceLoginPage({
  searchParams,
}: {
  searchParams: { email?: string | string[] };
}) {
  const email = typeof searchParams.email === 'string' ? searchParams.email : undefined;
  redirect(email ? `/employee/login?email=${encodeURIComponent(email)}` : '/employee/login');
}
