'use client';

import * as React from 'react';
import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { Menu, X } from 'lucide-react';
import { cn } from '@/lib/utils';
import { BRAND_SUBTITLE } from '@/lib/brand';
import { BrandMark } from '@/components/brand-mark';

/**
 * Sticky, glassy navbar shared across the marketing pages (rendered once in the layout). Real <Link>
 * navigation with a visible ACTIVE state per route; the glass deepens once the page is scrolled, and a mobile
 * menu holds the same links. No data, no auth — it never reads session state.
 */
const LINKS = [
  { href: '/', label: 'Product' },
  { href: '/features', label: 'Features' },
  { href: '/security', label: 'Security' },
  { href: '/support', label: 'Support' },
  { href: '/contact', label: 'Contact' },
] as const;

function isActive(pathname: string, href: string): boolean {
  return href === '/' ? pathname === '/' : pathname === href || pathname.startsWith(`${href}/`);
}

function Brand() {
  return (
    <Link href="/" className="flex items-center gap-2.5 pl-1">
      <BrandMark size={36} className="shadow-sm shadow-primary/40" />
      <span className="leading-tight">
        <span className="block text-[15px] font-semibold tracking-tight text-foreground">hrorg.in</span>
        <span className="block text-[10px] text-muted-foreground/70">{BRAND_SUBTITLE}</span>
      </span>
    </Link>
  );
}

export function MarketingNav() {
  const pathname = usePathname() ?? '/';
  const [scrolled, setScrolled] = React.useState(false);
  const [open, setOpen] = React.useState(false);

  React.useEffect(() => {
    const onScroll = () => setScrolled(window.scrollY > 12);
    onScroll();
    window.addEventListener('scroll', onScroll, { passive: true });
    return () => window.removeEventListener('scroll', onScroll);
  }, []);

  // Close the mobile menu whenever the route changes.
  React.useEffect(() => setOpen(false), [pathname]);

  return (
    <header className="fixed inset-x-0 top-0 z-50 flex justify-center px-4 pt-3 sm:pt-4">
      <nav
        className={cn(
          'w-full max-w-6xl rounded-2xl border transition-all duration-300',
          scrolled || open ? 'm-glass border-white/10 shadow-lg shadow-black/30' : 'border-transparent bg-transparent',
        )}
      >
        <div className="flex items-center justify-between px-3 py-2 sm:px-4">
          <Brand />

          <div className="hidden items-center gap-1 md:flex">
            {LINKS.map((l) => {
              const active = isActive(pathname, l.href);
              return (
                <Link
                  key={l.href}
                  href={l.href}
                  aria-current={active ? 'page' : undefined}
                  className={cn(
                    'rounded-full px-3 py-1.5 text-sm transition-colors',
                    active
                      ? 'bg-primary/15 font-medium text-primary-bright'
                      : 'text-muted-foreground hover:bg-white/5 hover:text-foreground',
                  )}
                >
                  {l.label}
                </Link>
              );
            })}
          </div>

          <div className="flex items-center gap-2">
            {/* One general "Sign in" (→ /login); the audience-specific doors are reached from the emails
                that carry the company slug. Nothing on the public site links to the onboarding door (§6). */}
            <Link
              href="/login"
              className="rounded-full bg-primary px-4 py-1.5 text-sm font-medium text-primary-foreground shadow-sm shadow-primary/40 transition-colors hover:bg-primary/90"
            >
              Sign in
            </Link>
            <button
              type="button"
              aria-label={open ? 'Close menu' : 'Open menu'}
              aria-expanded={open}
              onClick={() => setOpen((v) => !v)}
              className="flex size-9 items-center justify-center rounded-full border border-white/10 text-foreground transition-colors hover:bg-white/5 md:hidden"
            >
              {open ? <X className="size-4" /> : <Menu className="size-4" />}
            </button>
          </div>
        </div>

        {/* Mobile menu */}
        {open ? (
          <div className="border-t border-white/10 px-2 py-2 md:hidden">
            <div className="flex flex-col">
              {LINKS.map((l) => {
                const active = isActive(pathname, l.href);
                return (
                  <Link
                    key={l.href}
                    href={l.href}
                    aria-current={active ? 'page' : undefined}
                    className={cn(
                      'rounded-lg px-3 py-2.5 text-sm transition-colors',
                      active
                        ? 'bg-primary/15 font-medium text-primary-bright'
                        : 'text-muted-foreground hover:bg-white/5 hover:text-foreground',
                    )}
                  >
                    {l.label}
                  </Link>
                );
              })}
              <Link
                href="/login"
                className="rounded-lg px-3 py-2.5 text-sm text-muted-foreground transition-colors hover:bg-white/5 hover:text-foreground"
              >
                Sign in
              </Link>
            </div>
          </div>
        ) : null}
      </nav>
    </header>
  );
}
