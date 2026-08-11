import * as React from 'react';
import Link from 'next/link';
import { ArrowRight, Sparkles } from 'lucide-react';
import { Reveal } from '@/components/marketing/motion';

/**
 * The home page's closing call to action — a sign-in panel over an aurora backdrop with the subscription line
 * (access is by arrangement) beneath the two sign-in doors, which lands on /contact.
 */
export function FinalCta() {
  return (
    <section className="relative overflow-hidden border-t border-white/5 px-5 py-20 sm:px-8 md:py-28">
      <div aria-hidden className="pointer-events-none absolute inset-0 m-aurora opacity-60" />
      <div className="relative mx-auto max-w-4xl">
        <Reveal>
          <div className="m-glass overflow-hidden rounded-3xl p-8 text-center shadow-2xl shadow-black/40 sm:p-12">
            <span className="inline-flex items-center gap-2 rounded-full border border-white/10 bg-white/5 px-3 py-1 text-xs font-medium text-primary-bright">
              <Sparkles className="size-3.5" />
              Ready when you are
            </span>
            <h2 className="mt-5 text-balance text-3xl font-semibold tracking-tight text-foreground sm:text-4xl">
              Pick up right where you <span className="text-gradient">left off</span>.
            </h2>
            <p className="mx-auto mt-4 max-w-lg text-pretty text-muted-foreground">
              Sign in to your workspace to carry on.
            </p>
            <div className="mt-8 flex flex-wrap items-center justify-center gap-3">
              <Link
                href="/login"
                className="inline-flex items-center gap-2 rounded-full bg-primary px-6 py-3 text-sm font-semibold text-primary-foreground shadow-lg shadow-primary/30 transition-transform hover:-translate-y-0.5"
              >
                Sign in
                <ArrowRight className="size-4" />
              </Link>
            </div>
            <p className="mt-4 text-sm text-muted-foreground">
              Not subscribed yet?{' '}
              <Link
                href="/contact"
                className="font-medium text-primary-bright underline-offset-4 hover:underline"
              >
                Contact us
              </Link>
            </p>
          </div>
        </Reveal>
      </div>
    </section>
  );
}
