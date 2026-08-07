import * as React from 'react';
import Link from 'next/link';
import { Mail } from 'lucide-react';
import { BrandMark } from '@/components/brand-mark';

/**
 * Shared slim footer for every marketing page (rendered once in the (marketing) layout). Brand + subtitle,
 * real <Link> navigation matching the navbar, the two sign-in doors, and the contact address. No fake
 * contact/social, no invented company details.
 */
const FOOTER_LINKS = [
  { href: '/', label: 'Product' },
  { href: '/features', label: 'Features' },
  { href: '/security', label: 'Security' },
  { href: '/contact', label: 'Contact' },
] as const;

export function SiteFooter() {
  return (
    <footer className="border-t border-white/5 px-5 py-12 sm:px-8">
      <div className="mx-auto max-w-6xl">
        <div className="flex flex-col items-center justify-between gap-6 sm:flex-row">
          <Link href="/" className="flex items-center gap-2.5">
            <BrandMark size={36} />
            <span className="leading-tight">
              <span className="block text-sm font-semibold text-foreground">hrorg.in</span>
              <span className="block text-[11px] text-muted-foreground/70">Internal HR management services</span>
            </span>
          </Link>

          <nav className="flex flex-wrap items-center justify-center gap-x-5 gap-y-2 text-sm text-muted-foreground">
            {FOOTER_LINKS.map((l) => (
              <Link key={l.href} href={l.href} className="transition-colors hover:text-foreground">
                {l.label}
              </Link>
            ))}
            <Link href="/login" className="transition-colors hover:text-foreground">
              Sign in
            </Link>
            <a
              href="mailto:info@hrorg.in"
              className="inline-flex items-center gap-1.5 text-primary-bright transition-colors hover:text-foreground"
            >
              <Mail className="size-3.5" />
              info@hrorg.in
            </a>
          </nav>
        </div>

        <p className="mt-6 text-center text-xs text-muted-foreground/70">
          © hrorg.in · Internal HR management services.
        </p>
      </div>
    </footer>
  );
}
