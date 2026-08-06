'use client';

import * as React from 'react';
import Link from 'next/link';
import { ShieldCheck } from 'lucide-react';
import { cn } from '@/lib/utils';

/**
 * Sticky, glassy anchor nav for the one-pager. Client-only for the scroll state (the glass deepens once the
 * hero is passed). Anchors smooth-scroll to in-page sections; the two sign-in doors are the only real routes
 * it links to (/login, /employee/login). No data, no auth — it never reads session state.
 */
const LINKS = [
  { href: '#product', label: 'Product' },
  { href: '#workflow', label: 'Workflow' },
  { href: '#features', label: 'Features' },
  { href: '#security', label: 'Security' },
] as const;

export function MarketingNav() {
  const [scrolled, setScrolled] = React.useState(false);
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
          'flex w-full max-w-6xl items-center justify-between rounded-full border px-3 py-2 transition-all duration-300 sm:px-4',
          scrolled
            ? 'm-glass border-white/10 shadow-lg shadow-black/30'
            : 'border-transparent bg-transparent',
        )}
      >
        <Link href="#top" className="flex items-center gap-2.5 pl-1">
          <span className="flex size-8 items-center justify-center rounded-lg bg-primary text-primary-foreground shadow-sm shadow-primary/40">
            <ShieldCheck className="size-[1.125rem]" />
          </span>
          <span className="text-[15px] font-semibold tracking-tight text-foreground">IHRMS</span>
        </Link>

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
            className="hidden rounded-full px-3 py-1.5 text-sm text-muted-foreground transition-colors hover:text-foreground sm:inline-block"
          >
            Employee
          </Link>
          <Link
            href="/login"
            className="rounded-full bg-primary px-4 py-1.5 text-sm font-medium text-primary-foreground shadow-sm shadow-primary/40 transition-colors hover:bg-primary/90"
          >
            Sign in
          </Link>
        </div>
      </nav>
    </header>
  );
}
