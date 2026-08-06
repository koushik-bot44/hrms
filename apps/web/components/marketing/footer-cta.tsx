import * as React from 'react';
import Link from 'next/link';
import { ArrowRight, Mail, ShieldCheck } from 'lucide-react';
import { Reveal } from '@/components/marketing/motion';

/**
 * §7 — final CTA + footer. A sign-in panel over an aurora backdrop with the subscription line beneath the two
 * doors, then a slim footer with the brand + subtitle, anchors, the two real sign-in doors, and the contact
 * address. No fake contact/social, no invented company details.
 */
const FOOTER_LINKS = [
  { href: '#product', label: 'Product' },
  { href: '#features', label: 'Features' },
  { href: '#security', label: 'Security' },
  { href: '#contact', label: 'Contact' },
] as const;

export function FooterCta() {
  return (
    <footer className="relative overflow-hidden border-t border-white/5 px-5 py-20 sm:px-8 md:py-28">
      <div aria-hidden className="pointer-events-none absolute inset-0 m-aurora opacity-60" />

      <div className="relative mx-auto max-w-4xl">
        <Reveal>
          <div className="m-glass overflow-hidden rounded-3xl p-8 text-center shadow-2xl shadow-black/40 sm:p-12">
            <span className="inline-flex items-center gap-2 rounded-full border border-white/10 bg-white/5 px-3 py-1 text-xs font-medium text-primary-bright">
              <ShieldCheck className="size-3.5" />
              Ready when you are
            </span>
            <h2 className="mt-5 text-balance text-3xl font-semibold tracking-tight text-foreground sm:text-4xl">
              Hand off your internal HR — <span className="text-gradient">run and secured</span>.
            </h2>
            <p className="mx-auto mt-4 max-w-lg text-pretty text-muted-foreground">
              Sign in to your workspace to continue. Employees have their own door.
            </p>
            <div className="mt-8 flex flex-wrap items-center justify-center gap-3">
              <Link
                href="/login"
                className="inline-flex items-center gap-2 rounded-full bg-primary px-6 py-3 text-sm font-semibold text-primary-foreground shadow-lg shadow-primary/30 transition-transform hover:-translate-y-0.5"
              >
                Sign in
                <ArrowRight className="size-4" />
              </Link>
              <Link
                href="/employee/login"
                className="inline-flex items-center gap-2 rounded-full border border-white/15 bg-white/5 px-6 py-3 text-sm font-semibold text-foreground transition-colors hover:bg-white/10"
              >
                Employee sign-in
              </Link>
            </div>
            <p className="mt-4 text-sm text-muted-foreground">
              Not subscribed yet?{' '}
              <a href="#contact" className="font-medium text-primary-bright underline-offset-4 hover:underline">
                Contact us
              </a>
            </p>
          </div>
        </Reveal>

        {/* slim footer */}
        <div className="mt-14 flex flex-col items-center justify-between gap-6 border-t border-white/5 pt-8 sm:flex-row">
          <div className="flex items-center gap-2.5">
            <span className="flex size-9 items-center justify-center rounded-lg bg-primary text-primary-foreground">
              <ShieldCheck className="size-4" />
            </span>
            <div className="leading-tight">
              <div className="text-sm font-semibold text-foreground">hrorg.in</div>
              <div className="text-[11px] text-muted-foreground/70">Internal HR management services</div>
            </div>
          </div>

          <nav className="flex flex-wrap items-center justify-center gap-x-5 gap-y-2 text-sm text-muted-foreground">
            {FOOTER_LINKS.map((l) => (
              <a key={l.href} href={l.href} className="transition-colors hover:text-foreground">
                {l.label}
              </a>
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
