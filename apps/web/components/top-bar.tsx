'use client';

import * as React from 'react';
import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { AnimatePresence, motion } from 'framer-motion';
import { Menu, ShieldCheck, X } from 'lucide-react';
import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import { ApiStatusIndicator } from '@/components/api-status-indicator';

const NAV = [
  { href: '/verify', label: 'Verify' },
  { href: '/candidate', label: 'Candidate' },
  { href: '/employee', label: 'Employee' },
  { href: '/hr', label: 'HR' },
] as const;

export function TopBar() {
  const pathname = usePathname();
  const [open, setOpen] = React.useState(false);

  const isActive = (href: string) => pathname === href || pathname.startsWith(`${href}/`);

  return (
    <header className="sticky top-0 z-40 w-full border-b bg-background/90 backdrop-blur supports-[backdrop-filter]:bg-background/75">
      <div className="container flex h-16 items-center justify-between gap-4">
        <Link
          href="/"
          className="flex items-center gap-2 font-medium"
          onClick={() => setOpen(false)}
        >
          <ShieldCheck className="size-5 text-primary" />
          <span className="tracking-tight">CDPP</span>
          <span className="hidden text-xs font-normal text-muted-foreground sm:inline">
            Document Provisioning
          </span>
        </Link>

        <nav className="hidden items-center gap-1 md:flex" aria-label="Primary">
          {NAV.map((item) => (
            <Link
              key={item.href}
              href={item.href}
              className={cn(
                'rounded-md px-3 py-2 text-sm font-medium transition-colors hover:bg-accent hover:text-accent-foreground',
                isActive(item.href) ? 'bg-accent text-accent-foreground' : 'text-muted-foreground',
              )}
            >
              {item.label}
            </Link>
          ))}
        </nav>

        <div className="hidden md:flex">
          <ApiStatusIndicator />
        </div>

        <Button
          variant="ghost"
          size="icon"
          className="md:hidden"
          aria-label={open ? 'Close menu' : 'Open menu'}
          aria-expanded={open}
          onClick={() => setOpen((v) => !v)}
        >
          {open ? <X /> : <Menu />}
        </Button>
      </div>

      <AnimatePresence initial={false}>
        {open ? (
          <motion.nav
            key="mobile-nav"
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: 'auto', opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            transition={{ duration: 0.2, ease: 'easeOut' }}
            className="overflow-hidden border-b bg-background md:hidden"
            aria-label="Mobile"
          >
            <div className="container flex flex-col gap-1 py-3">
              {NAV.map((item) => (
                <Link
                  key={item.href}
                  href={item.href}
                  onClick={() => setOpen(false)}
                  className={cn(
                    'rounded-md px-3 py-2 text-sm font-medium',
                    isActive(item.href)
                      ? 'bg-accent text-accent-foreground'
                      : 'text-muted-foreground hover:bg-accent',
                  )}
                >
                  {item.label}
                </Link>
              ))}
              <div className="px-3 py-2">
                <ApiStatusIndicator />
              </div>
            </div>
          </motion.nav>
        ) : null}
      </AnimatePresence>
    </header>
  );
}
