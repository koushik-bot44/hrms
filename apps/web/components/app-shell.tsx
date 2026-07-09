'use client';

import * as React from 'react';
import Link from 'next/link';
import { usePathname, useRouter } from 'next/navigation';
import { KeyRound, LogOut, Menu, ShieldCheck, UserRound } from 'lucide-react';
import { cn } from '@/lib/utils';
import { useAuth } from '@/components/auth-provider';
import { ChangePasswordDialog } from '@/components/change-password-dialog';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { ApiStatusIndicator } from '@/components/api-status-indicator';
import { MailButton } from '@/components/mail/mail-button';

export interface NavItem {
  label: string;
  href: string;
  icon: React.ComponentType<{ className?: string }>;
}

function NavLinks({ items, onNavigate }: { items: NavItem[]; onNavigate?: () => void }) {
  const pathname = usePathname();
  // Only the most specific (longest) matching item is active, so an index route like
  // "/hr" doesn't also light up while you're on "/hr/employees" (one highlight at a time).
  const activeHref = React.useMemo(() => {
    let best: string | null = null;
    for (const item of items) {
      const matches = pathname === item.href || pathname.startsWith(`${item.href}/`);
      if (matches && (best === null || item.href.length > best.length)) {
        best = item.href;
      }
    }
    return best;
  }, [items, pathname]);

  return (
    <nav className="flex flex-col gap-1" aria-label="Primary">
      {items.map((item) => {
        const active = item.href === activeHref;
        return (
          <Link
            key={item.href}
            href={item.href}
            onClick={onNavigate}
            aria-current={active ? 'page' : undefined}
            className={cn(
              'flex items-center gap-3 rounded-md px-3 py-2 text-sm font-medium transition-colors',
              active
                ? 'bg-primary/10 text-primary'
                : 'text-muted-foreground hover:bg-accent hover:text-accent-foreground',
            )}
          >
            <item.icon className="size-4 shrink-0" />
            {item.label}
          </Link>
        );
      })}
    </nav>
  );
}

function Brand({ roleLabel }: { roleLabel: string }) {
  return (
    <div className="flex h-14 items-center gap-2 border-b px-4">
      <div className="flex size-7 items-center justify-center rounded-md bg-primary text-primary-foreground">
        <ShieldCheck className="size-4" />
      </div>
      <div className="leading-tight">
        <div className="text-sm font-semibold tracking-tight">IHRMS</div>
        <div className="text-[11px] text-muted-foreground">{roleLabel}</div>
      </div>
    </div>
  );
}

function UserMenu({ roleLabel }: { roleLabel: string }) {
  const { session, logout } = useAuth();
  const router = useRouter();
  const [changingPassword, setChangingPassword] = React.useState(false);
  const isStaff = session?.type === 'USER';

  const displayName =
    session?.type === 'USER'
      ? session.name || session.email
      : session?.type === 'EMPLOYEE'
        ? session.employeeCode
        : roleLabel;
  const subtitle = session?.type === 'USER' ? session.email : session?.email;

  const onSignOut = async () => {
    const wasEmployee = session?.type === 'EMPLOYEE';
    await logout();
    router.replace(wasEmployee ? '/employee/login' : '/login');
  };

  return (
    <>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="ghost" size="sm" className="gap-2">
            <span className="flex size-7 items-center justify-center rounded-full bg-muted text-muted-foreground">
              <UserRound className="size-4" />
            </span>
            <span className="hidden max-w-[12rem] truncate text-sm sm:inline">{displayName}</span>
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-56">
          <DropdownMenuLabel className="flex flex-col">
            <span className="truncate">{displayName}</span>
            {subtitle ? (
              <span className="text-xs font-normal text-muted-foreground">{subtitle}</span>
            ) : null}
          </DropdownMenuLabel>
          <DropdownMenuSeparator />
          {isStaff ? (
            <DropdownMenuItem onSelect={() => setChangingPassword(true)}>
              <KeyRound className="size-4" />
              Change password
            </DropdownMenuItem>
          ) : null}
          <DropdownMenuItem onSelect={() => void onSignOut()}>
            <LogOut className="size-4" />
            Sign out
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
      {isStaff ? (
        <ChangePasswordDialog open={changingPassword} onOpenChange={setChangingPassword} />
      ) : null}
    </>
  );
}

export function AppShell({
  roleLabel,
  nav,
  children,
}: {
  roleLabel: string;
  nav: NavItem[];
  children: React.ReactNode;
}) {
  const [mobileOpen, setMobileOpen] = React.useState(false);

  return (
    <div className="flex min-h-dvh bg-background">
      {/* Desktop sidebar */}
      <aside className="hidden w-60 shrink-0 flex-col border-r bg-card md:flex">
        <Brand roleLabel={roleLabel} />
        <div className="flex-1 overflow-y-auto p-3">
          <NavLinks items={nav} />
        </div>
      </aside>

      <div className="flex min-w-0 flex-1 flex-col">
        {/* Topbar */}
        <header className="flex h-14 items-center justify-between gap-3 border-b bg-background px-4 md:px-6">
          <div className="flex items-center gap-2">
            <Dialog open={mobileOpen} onOpenChange={setMobileOpen}>
              <DialogTrigger asChild>
                <Button variant="ghost" size="icon" className="md:hidden" aria-label="Open menu">
                  <Menu />
                </Button>
              </DialogTrigger>
              <DialogContent className="left-0 top-0 h-dvh max-w-[16rem] translate-x-0 translate-y-0 gap-0 rounded-none p-0 sm:rounded-none">
                <DialogTitle className="sr-only">Navigation</DialogTitle>
                <Brand roleLabel={roleLabel} />
                <div className="p-3">
                  <NavLinks items={nav} onNavigate={() => setMobileOpen(false)} />
                </div>
              </DialogContent>
            </Dialog>
            <span className="text-sm font-semibold tracking-tight md:hidden">IHRMS</span>
          </div>
          <div className="flex items-center gap-3">
            <ApiStatusIndicator className="hidden sm:inline-flex" />
            <MailButton />
            <UserMenu roleLabel={roleLabel} />
          </div>
        </header>

        <main className="flex-1 overflow-y-auto">
          <div className="mx-auto max-w-6xl animate-fade-in p-4 md:p-8">{children}</div>
        </main>
      </div>
    </div>
  );
}
