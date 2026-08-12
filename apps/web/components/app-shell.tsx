'use client';

import * as React from 'react';
import Link from 'next/link';
import { usePathname, useRouter } from 'next/navigation';
import {
  Bell,
  KeyRound,
  Lock,
  LogOut,
  Menu,
  PanelLeftClose,
  PanelLeftOpen,
  UserRound,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import { useAuth } from '@/components/auth-provider';
import { ChangePasswordDialog } from '@/components/change-password-dialog';
import { NotificationsDialog } from '@/components/push/notifications-dialog';
import { Button } from '@/components/ui/button';
import { useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog';
import { clockOut as apiClockOut, attendanceKeys } from '@/lib/api/attendance';
import {
  useBeforeUnloadWhenClockedIn,
  useClockStatus,
} from '@/components/attendance/use-clock-status';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { MailButton } from '@/components/mail/mail-button';
import { BrandWordmark } from '@/components/brand-wordmark';
import { BrandMark } from '@/components/brand-mark';
import { SidebarWaves } from '@/components/sidebar-waves';
import { AppPrompts } from '@/components/pwa/app-prompts';

export interface NavItem {
  label: string;
  href: string;
  icon: React.ComponentType<{ className?: string }>;
  /** Optional count pill shown after the label (e.g. pending approvals); hidden when 0/undefined. */
  badge?: number;
}

function NavLinks({
  items,
  onNavigate,
  collapsed = false,
}: {
  items: NavItem[];
  onNavigate?: () => void;
  collapsed?: boolean;
}) {
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
    <nav className="flex flex-col gap-1.5" aria-label="Primary">
      {items.map((item) => {
        const active = item.href === activeHref;
        return (
          <Link
            key={item.href}
            href={item.href}
            onClick={onNavigate}
            aria-current={active ? 'page' : undefined}
            title={collapsed ? item.label : undefined}
            className={cn(
              'flex items-center gap-3 rounded-xl text-sm font-medium transition-colors duration-150',
              collapsed ? 'justify-center px-0 py-2.5' : 'px-3.5 py-2.5',
              active
                ? 'bg-sidebar-active text-sidebar-active-foreground shadow-sm'
                : 'text-sidebar-muted hover:bg-white/5 hover:text-sidebar-foreground',
            )}
          >
            <item.icon className="size-4 shrink-0" />
            {collapsed ? null : <span className="flex-1">{item.label}</span>}
            {!collapsed && item.badge ? (
              <span className="inline-flex min-w-5 items-center justify-center rounded-full bg-warning/20 px-1.5 text-xs font-semibold text-warning">
                {item.badge}
              </span>
            ) : null}
          </Link>
        );
      })}
    </nav>
  );
}

function Brand({ roleLabel, collapsed = false }: { roleLabel: string; collapsed?: boolean }) {
  return (
    <div
      className={cn(
        'flex h-14 items-center gap-2 border-b border-sidebar-border',
        collapsed ? 'justify-center px-2' : 'px-4',
      )}
    >
      <BrandMark size={32} className="shadow-sm ring-1 ring-inset ring-white/10" />
      {collapsed ? null : (
        <div className="min-w-0 leading-tight">
          <BrandWordmark className="text-sm font-semibold tracking-tight text-sidebar-foreground" />
          <div className="text-[11px] text-sidebar-muted">{roleLabel}</div>
        </div>
      )}
    </div>
  );
}

/** Static, decorative trust badge pinned to the sidebar footer (per the design direction). */
function SecurityBadge() {
  return (
    <div className="flex items-start gap-2.5 rounded-xl bg-black/20 p-3 ring-1 ring-inset ring-sidebar-border">
      <Lock className="mt-0.5 size-4 shrink-0 text-primary-bright" aria-hidden />
      <div className="leading-snug">
        <p className="text-xs font-semibold text-sidebar-foreground">Secure &amp; compliant</p>
        <p className="mt-0.5 text-[11px] text-sidebar-muted">
          Your data is protected with enterprise-grade security and encryption.
        </p>
      </div>
    </div>
  );
}

/** Topbar welcome cluster — REAL session data only (name/code + role). */
function WelcomeCluster({ roleLabel }: { roleLabel: string }) {
  const { session } = useAuth();
  const name =
    session?.type === 'USER'
      ? session.name || session.email
      : session?.type === 'EMPLOYEE'
        ? session.name || session.employeeCode
        : null;
  return (
    <div className="hidden min-w-0 leading-tight md:block">
      <p className="truncate text-sm font-semibold tracking-tight">
        {name ? <>Welcome back, {name}</> : 'Welcome back'}
      </p>
      <p className="truncate text-xs text-muted-foreground">{roleLabel}</p>
    </div>
  );
}

function UserMenu({ roleLabel }: { roleLabel: string }) {
  const { session, logout } = useAuth();
  const router = useRouter();
  const queryClient = useQueryClient();
  const [changingPassword, setChangingPassword] = React.useState(false);
  const [notificationsOpen, setNotificationsOpen] = React.useState(false);
  const [confirmClockOut, setConfirmClockOut] = React.useState(false);
  const [clockingOut, setClockingOut] = React.useState(false);
  const isStaff = session?.type === 'USER';
  // Web Push (§ Web Push): only a mailbox principal can subscribe — staff always, an employee once
  // credentialed. An uncredentialed (OTP-only) employee can't, so we don't offer the control to them.
  const hasMailbox = isStaff || (session?.type === 'EMPLOYEE' && Boolean(session.mailAddress));

  // Clock-out reminders (§8a): while an employee has an open session, warn on sign-out + tab close.
  const status = useClockStatus();
  const clockedIn = status.data?.open ?? false;
  useBeforeUnloadWhenClockedIn(clockedIn);

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

  // Intercept sign-out: if still clocked in, confirm first (reliable, unlike the tab-close nudge).
  const requestSignOut = () => {
    if (clockedIn) {
      setConfirmClockOut(true);
    } else {
      void onSignOut();
    }
  };

  const clockOutAndSignOut = async () => {
    setClockingOut(true);
    try {
      await apiClockOut();
      void queryClient.invalidateQueries({ queryKey: attendanceKeys.status });
    } catch {
      toast.error('Could not log out — signing out anyway');
    } finally {
      setClockingOut(false);
      setConfirmClockOut(false);
      void onSignOut();
    }
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
          {hasMailbox ? (
            <DropdownMenuItem onSelect={() => setNotificationsOpen(true)}>
              <Bell className="size-4" />
              Notifications
            </DropdownMenuItem>
          ) : null}
          <DropdownMenuItem onSelect={requestSignOut}>
            <LogOut className="size-4" />
            Sign out
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
      {isStaff ? (
        <ChangePasswordDialog open={changingPassword} onOpenChange={setChangingPassword} />
      ) : null}
      {hasMailbox ? (
        <NotificationsDialog open={notificationsOpen} onOpenChange={setNotificationsOpen} />
      ) : null}

      {/* Still-clocked-in confirm on sign-out (§8a). */}
      <Dialog open={confirmClockOut} onOpenChange={setConfirmClockOut}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>You&rsquo;re still logged in</DialogTitle>
            <DialogDescription>
              You have an open attendance session. Log out before you sign out?
            </DialogDescription>
          </DialogHeader>
          <div className="flex flex-col-reverse gap-2 pt-2 sm:flex-row sm:justify-end">
            <Button variant="ghost" onClick={() => setConfirmClockOut(false)} disabled={clockingOut}>
              Cancel
            </Button>
            <Button
              variant="outline"
              onClick={() => {
                setConfirmClockOut(false);
                void onSignOut();
              }}
              disabled={clockingOut}
            >
              Sign out anyway
            </Button>
            <Button onClick={() => void clockOutAndSignOut()} disabled={clockingOut}>
              {clockingOut ? 'Logging out…' : 'Log out & sign out'}
            </Button>
          </div>
        </DialogContent>
      </Dialog>
    </>
  );
}

export function AppShell({
  roleLabel,
  nav,
  children,
  showMail = true,
}: {
  roleLabel: string;
  nav: NavItem[];
  children: React.ReactNode;
  /** Show the topbar Mail button. Off in the onboarding area — the mailbox lives in the portal (§8). */
  showMail?: boolean;
}) {
  const [mobileOpen, setMobileOpen] = React.useState(false);
  const [collapsed, setCollapsed] = React.useState(false);
  const pathname = usePathname();

  // Persisted collapse — loaded after mount (client-only) to avoid a hydration mismatch.
  React.useEffect(() => {
    try {
      setCollapsed(localStorage.getItem('ihrms.sidebarCollapsed') === '1');
    } catch {
      /* localStorage unavailable — default expanded */
    }
  }, []);
  const toggleCollapsed = () =>
    setCollapsed((c) => {
      const next = !c;
      try {
        localStorage.setItem('ihrms.sidebarCollapsed', next ? '1' : '0');
      } catch {
        /* ignore */
      }
      return next;
    });

  return (
    <div className="flex min-h-dvh bg-background">
      {/* Desktop sidebar — its own deep indigo-navy surface (dark in both app modes). */}
      <aside
        className={cn(
          'relative hidden shrink-0 flex-col overflow-hidden border-r border-sidebar-border bg-sidebar text-sidebar-foreground md:flex',
          collapsed ? 'w-16' : 'w-60',
        )}
      >
        {/* Decorative soft-indigo wash + the wave-line texture near the footer (behind content). */}
        <div className="sidebar-gradient pointer-events-none absolute inset-x-0 bottom-0 h-56" aria-hidden />
        {collapsed ? null : <SidebarWaves />}
        <Brand roleLabel={roleLabel} collapsed={collapsed} />
        <div className="relative flex-1 overflow-y-auto p-3">
          <NavLinks items={nav} collapsed={collapsed} />
        </div>
        <div className="relative space-y-3 border-t border-sidebar-border p-3">
          {collapsed ? null : <SecurityBadge />}
          <button
            type="button"
            onClick={toggleCollapsed}
            aria-expanded={!collapsed}
            aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
            title={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
            className={cn(
              'flex w-full items-center gap-3 rounded-xl px-3.5 py-2.5 text-sm font-medium text-sidebar-muted transition-colors duration-150 hover:bg-white/5 hover:text-sidebar-foreground',
              collapsed && 'justify-center px-0',
            )}
          >
            {collapsed ? (
              <PanelLeftOpen className="size-4 shrink-0" />
            ) : (
              <>
                <PanelLeftClose className="size-4 shrink-0" />
                Collapse
              </>
            )}
          </button>
        </div>
      </aside>

      <div className="flex min-w-0 flex-1 flex-col">
        {/* Topbar — welcome cluster on the light page surface. `pt` respects the notch/status bar when the
            app is installed to the home screen (env() is 0 otherwise, so no change in a normal browser tab). */}
        <header className="flex min-h-14 items-center justify-between gap-3 border-b bg-background px-4 pt-[env(safe-area-inset-top)] md:px-6">
          <div className="flex min-w-0 items-center gap-2">
            <Dialog open={mobileOpen} onOpenChange={setMobileOpen}>
              <DialogTrigger asChild>
                <Button variant="ghost" size="icon" className="md:hidden" aria-label="Open menu">
                  <Menu />
                </Button>
              </DialogTrigger>
              <DialogContent className="left-0 top-0 flex h-dvh max-h-dvh w-[16rem] max-w-[16rem] translate-x-0 translate-y-0 flex-col gap-0 rounded-none border-sidebar-border bg-sidebar p-0 pt-[env(safe-area-inset-top)] text-sidebar-foreground sm:rounded-none">
                <DialogTitle className="sr-only">Navigation</DialogTitle>
                <Brand roleLabel={roleLabel} />
                <div className="flex-1 overflow-y-auto p-3">
                  <NavLinks items={nav} onNavigate={() => setMobileOpen(false)} />
                </div>
                <div className="border-t border-sidebar-border p-3">
                  <SecurityBadge />
                </div>
              </DialogContent>
            </Dialog>
            <BrandWordmark className="text-sm font-semibold tracking-tight md:hidden" />
            <WelcomeCluster roleLabel={roleLabel} />
          </div>
          <div className="flex items-center gap-3">
            {showMail ? <MailButton /> : null}
            <UserMenu roleLabel={roleLabel} />
          </div>
        </header>

        <main className="flex-1 overflow-y-auto pb-[env(safe-area-inset-bottom)]">
          {/* Keyed on the route so the subtle page-enter replays on navigation. Roomier vertical rhythm on
              mobile ("spacious"), scaling up on larger screens. */}
          <div
            key={pathname}
            className="mx-auto max-w-6xl animate-page-enter px-4 py-6 sm:px-6 sm:py-8 md:p-8"
          >
            {children}
          </div>
        </main>
      </div>

      {/* Post-sign-in nudge: turn on notifications + add to home screen (once, dismissible). */}
      <AppPrompts />
    </div>
  );
}
