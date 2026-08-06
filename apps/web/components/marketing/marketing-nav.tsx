'use client';

import * as React from 'react';
import Link from 'next/link';
import { Menu, ShieldCheck, X } from 'lucide-react';
import { cn } from '@/lib/utils';

/**
 * Sticky, glassy navbar for the one-pager. Client-only for the scroll state (the glass deepens once the hero
 * is passed) and the mobile menu. Anchors smooth-scroll to in-page sections; the two sign-in doors are the
 * only real routes it links to (/login, /employee/login). No data, no auth — it never reads session state.
 */
const LINKS = [
  { href: '#product', label: 'Product' },
  { href: '#features', label: 'Features' },
  { href: '#security', label: 'Security' },
  { href: '#contact', label: 'Contact' },
] as const;

function Brand() {
  return (
    <Link href="#top" className="flex items-center gap-2.5 pl-1">
      <span className="flex size-9 items-center justify-center rounded-lg bg-primary text-primary-foreground shadow-sm shadow-primary/40">
        <ShieldCheck className="size-[1.125rem]" />
      </span>
      <span className="leading-tight">
        <span className="block text-[15px] font-semibold tracking-tight text-foreground">hrorg.in</span>
        <span className="block text-[10px] text-muted-foreground/70">Internal HR management services</span>
      </span>
    </Link>
  );
}

export function MarketingNav() {
  const [scrolled, setScrolled] = React.useState(false);
  const [open, setOpen] = React.useState(false);

  React.useEffect(() => {
    const onScroll = () => setScrolled(window.scrollY > 12);
    onScroll();
    window.addEventListener('scroll', onScroll, { passive: true });
    return () => window.removeEventListener('scroll', onScroll);
  }, []);

  return (
    <header className="fixed inset-x-0 top-0 z-50 flex justify-center px-4 pt-3 sm:pt-4">
      <nav
        className={cn(
          'w-full max-w-6xl rounded-2xl border transition-all duration-300',
          scrolled || open
            ? 'm-glass border-white/10 shadow-lg shadow-black/30'
            : 'border-transparent bg-transparent',
        )}
      >
        <div className="flex items-center justify-between px-3 py-2 sm:px-4">
          <Brand />

          <div className="hidden items-center gap-1 md:flex">
            {LINKS.map((l) => (
              <a
                key={l.href}
                href={l.href}
                className="rounded-full px-3 py-1.5 text-sm text-muted-foreground transition-colors hover:bg-white/5 hover:text-foreground"
              >
                {l.label}
              </a>
            ))}
          </div>

          <div className="flex items-center gap-2">
            <Link
              href="/employee/login"
              className="hidden rounded-full px-3 py-1.5 text-sm text-muted-foreground transition-colors hover:text-foreground lg:inline-block"
            >
              Employee
            </Link>
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
              {LINKS.map((l) => (
                <a
                  key={l.href}
                  href={l.href}
                  onClick={() => setOpen(false)}
                  className="rounded-lg px-3 py-2.5 text-sm text-muted-foreground transition-colors hover:bg-white/5 hover:text-foreground"
                >
                  {l.label}
                </a>
              ))}
              <Link
                href="/employee/login"
                onClick={() => setOpen(false)}
                className="rounded-lg px-3 py-2.5 text-sm text-muted-foreground transition-colors hover:bg-white/5 hover:text-foreground"
              >
                Employee sign-in
              </Link>
            </div>
          </div>
        ) : null}
      </nav>
    </header>
  );
}
