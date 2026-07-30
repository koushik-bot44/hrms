'use client';

import { useAuth } from '@/components/auth-provider';
import { cn } from '@/lib/utils';

/**
 * The session-driven brand wordmark, shared by the sidebar brand, the mobile top bar and the /mail rail.
 * It mirrors the URL scheme (Stage 2): a COMPANY-SCOPED session (Company Admin, HR, Manager, team
 * Accountant, Employee incl. onboarding) reads `hrorg.in/{companySlug}`; a PLATFORM session (Super Admin,
 * Accounts Admin, Hierarchy — no company exists) reads `hrorg.in`. Driven by the SESSION, not the route, so
 * the Accounts workspace — which dual-serves the slugged Accountant and the platform Accounts Admin — shows
 * the right line for whoever is signed in.
 *
 * `hrorg.in` always stays intact; a long slug truncates with an ellipsis (the full value lives in the
 * `title`) so the fixed-width sidebar never wraps or overflows. The collapsed rail renders no text and so
 * never mounts this.
 */
export function BrandWordmark({ className, suffix }: { className?: string; suffix?: string }) {
  const { session } = useAuth();
  const slug = session?.companySlug ?? null;
  const full = `${slug ? `hrorg.in/${slug}` : 'hrorg.in'}${suffix ? ` ${suffix}` : ''}`;

  return (
    <div className={cn('flex min-w-0 items-baseline', className)} title={full}>
      <span className="shrink-0">hrorg.in</span>
      {slug ? <span className="min-w-0 truncate">/{slug}</span> : null}
      {suffix ? <span className="shrink-0">&nbsp;{suffix}</span> : null}
    </div>
  );
}
