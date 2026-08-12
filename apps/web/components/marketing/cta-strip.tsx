import * as React from 'react';
import Link from 'next/link';
import { ArrowRight } from 'lucide-react';
import { Reveal } from '@/components/marketing/motion';

/**
 * A compact closing band for the sub-pages (Features / Security). Invites the visitor to Contact (where access
 * is arranged) with a secondary Sign-in link. Lighter than the home page's FinalCta.
 */
export function CtaStrip({
  title = 'Ready to bring your internal HR to hrorg.in?',
}: {
  title?: string;
}) {
  return (
    <section className="border-t border-white/5 px-5 py-16 sm:px-8">
      <Reveal className="mx-auto max-w-4xl">
        <div className="m-glass flex flex-col items-center justify-between gap-5 rounded-2xl p-6 text-center shadow-xl shadow-black/30 sm:flex-row sm:text-left">
          <p className="text-lg font-semibold text-foreground">{title}</p>
          <div className="flex flex-wrap items-center justify-center gap-3">
            <Link
              href="/contact"
              className="inline-flex min-h-11 items-center gap-2 rounded-full bg-primary px-5 py-2.5 text-sm font-semibold text-primary-foreground shadow-lg shadow-primary/30 transition-transform hover:-translate-y-0.5"
            >
              Contact us
              <ArrowRight className="size-4" />
            </Link>
            <Link
              href="/login"
              className="inline-flex min-h-11 items-center gap-2 rounded-full border border-white/15 bg-white/5 px-5 py-2.5 text-sm font-semibold text-foreground transition-colors hover:bg-white/10"
            >
              Sign in
            </Link>
          </div>
        </div>
      </Reveal>
    </section>
  );
}
