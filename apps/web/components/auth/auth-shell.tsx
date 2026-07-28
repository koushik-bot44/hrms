import * as React from 'react';
import { ShieldCheck } from 'lucide-react';
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from '@/components/ui/card';
import { SidebarWaves } from '@/components/sidebar-waves';
import { AuthIllustration } from '@/components/auth/auth-illustration';

/**
 * The shared editorial chrome for the two sign-in doors (staff password + employee OTP). Presentation
 * ONLY — the forms + all auth logic live in the pages and are passed as {@code children}. A full-height
 * SPLIT layout: a dark indigo-navy marketing panel on the left (its own dark surface in both app modes,
 * reusing the sidebar's visual vocabulary) and the light form-card panel on the right. On mobile the left
 * panel collapses to a compact brand header above the form. Door-appropriate copy comes through the
 * {@code portalLabel} / {@code badgeLabel} props; no image assets (the illustration is inline SVG).
 */
export function AuthShell({
  icon,
  title,
  description,
  children,
  footer,
  portalLabel = 'Integrated HR Management',
  badgeLabel = 'Secure access',
}: {
  icon: React.ReactNode;
  title: string;
  description: React.ReactNode;
  children: React.ReactNode;
  footer?: React.ReactNode;
  /** Left-panel sub-label under HRORGS (defaults to the "Integrated HR Management" tagline). */
  portalLabel?: string;
  /** Secure-badge heading (e.g. "Secure employee access" / "Secure staff access"). */
  badgeLabel?: string;
}) {
  return (
    <div className="flex min-h-dvh flex-col bg-background md:flex-row">
      {/* LEFT — dark indigo marketing panel; a compact brand header on mobile. */}
      <aside className="relative flex flex-col overflow-hidden border-b border-sidebar-border bg-sidebar px-6 py-6 text-sidebar-foreground md:w-[46%] md:border-b-0 md:border-r md:px-10 md:py-10 lg:px-14">
        {/* Decorative wave depth at the foot (reused shared component). */}
        <SidebarWaves />

        {/* Brand mark (glass tile) + portal sub-label. */}
        <div className="relative flex items-center gap-3">
          <div className="flex size-10 items-center justify-center rounded-xl bg-white/10 ring-1 ring-inset ring-white/20">
            <ShieldCheck className="size-5 text-primary-bright" aria-hidden />
          </div>
          <div className="leading-tight">
            <div className="text-sm font-semibold tracking-tight text-sidebar-foreground">HRORGS</div>
            <div className="text-[11px] text-sidebar-muted">{portalLabel}</div>
          </div>
        </div>

        {/* Headline + illustration — grows to fill on desktop, compact on mobile. */}
        <div className="relative flex flex-1 flex-col justify-center py-4 md:py-6">
          <h1 className="max-w-md text-2xl font-semibold leading-tight tracking-tight text-sidebar-foreground md:text-4xl">
            People power <span className="text-primary-bright">better</span> workplaces
          </h1>
          <p className="mt-2 max-w-sm text-sm text-sidebar-muted md:mt-3">
            A smarter way to manage people, time, and productivity.
          </p>
          <AuthIllustration className="mt-6 hidden max-w-md md:mt-8 md:block" />
        </div>

        {/* Secure badge — the sidebar's pattern, door-specific label. Desktop only (compact header on mobile). */}
        <div className="relative mt-2 hidden items-start gap-2.5 rounded-xl bg-black/20 p-3 ring-1 ring-inset ring-white/10 md:flex">
          <ShieldCheck className="mt-0.5 size-4 shrink-0 text-primary-bright" aria-hidden />
          <div className="leading-snug">
            <p className="text-xs font-semibold text-sidebar-foreground">{badgeLabel}</p>
            <p className="mt-0.5 text-[11px] text-sidebar-muted">
              Your data is protected with enterprise-grade security and encryption.
            </p>
          </div>
        </div>
      </aside>

      {/* RIGHT — the light form panel with the auth card. */}
      <main className="flex flex-1 items-center justify-center bg-background p-6 md:p-10">
        <Card className="w-full max-w-md animate-page-enter">
          <CardHeader className="items-center gap-1 pb-4 text-center">
            <div className="mb-2 flex size-12 items-center justify-center rounded-2xl bg-primary text-primary-foreground shadow-sm">
              {icon}
            </div>
            <CardTitle className="text-2xl">{title}</CardTitle>
            <CardDescription className="text-[15px] leading-relaxed">{description}</CardDescription>
          </CardHeader>
          <CardContent>{children}</CardContent>
          {footer ? <CardFooter className="justify-center pt-2">{footer}</CardFooter> : null}
        </Card>
      </main>
    </div>
  );
}
