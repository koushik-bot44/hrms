import * as React from 'react';
import Link from 'next/link';
import { ArrowRight, ShieldCheck } from 'lucide-react';
import { BrowserFrame } from '@/components/marketing/chrome';
import { HeroSceneMount } from '@/components/marketing/hero-scene-mount';
import { Reveal, Tilt } from '@/components/marketing/motion';
import { DashboardVignette } from '@/components/marketing/vignettes';

/**
 * HERO (§1). Server-rendered copy (present in the SSR HTML for SEO), with the interactive scene as a lazy
 * client backdrop and a flagship vignette floating in front. Positioning: a third party that runs and secures
 * client organizations' internal HR — outcomes, not internal architecture. Real claims only.
 */
const TRUST = [
  'Confidential by design',
  'Encrypted where sensitive',
  'Access-controlled',
  'Audit-ready records',
] as const;

export function Hero() {
  return (
    <section id="top" className="relative overflow-hidden px-5 pb-16 pt-28 sm:px-8 sm:pt-32 md:pb-24 md:pt-36">
      {/* decorative backdrops (behind everything) */}
      <div aria-hidden className="pointer-events-none absolute inset-0 m-aurora opacity-80" />
      <div aria-hidden className="pointer-events-none absolute inset-0 m-grid" />
      <div
        aria-hidden
        className="pointer-events-none absolute inset-x-0 top-0 h-[520px] opacity-70 [mask-image:radial-gradient(70%_70%_at_60%_30%,#000_35%,transparent_75%)]"
      >
        <HeroSceneMount className="h-full w-full" />
      </div>

      <div className="relative mx-auto grid w-full max-w-6xl items-center gap-12 lg:grid-cols-[1.05fr_0.95fr]">
        {/* copy */}
        <div className="max-w-xl">
          <span className="inline-flex items-center gap-2 rounded-full border border-white/10 bg-white/5 px-3 py-1 text-xs font-medium text-primary-bright">
            <ShieldCheck className="size-3.5" />
            Confidential by design
          </span>
          <h1 className="mt-5 text-balance text-4xl font-semibold leading-[1.05] tracking-tight text-foreground sm:text-5xl md:text-6xl">
            Your internal HR operations, run and <span className="text-gradient">secured end to end</span>.
          </h1>
          <p className="mt-5 text-pretty text-base leading-relaxed text-muted-foreground sm:text-lg">
            hrorg.in manages your people operations from onboarding to exit — e-signed employment documents,
            attendance and leave, internal communications, and audit-ready records. We secure how your HR runs,
            which is also why we don&apos;t publish how it works inside.
          </p>

          <div className="mt-8 flex flex-wrap items-center gap-3">
            <Link
              href="/features"
              className="inline-flex items-center gap-2 rounded-full bg-primary px-5 py-2.5 text-sm font-semibold text-primary-foreground shadow-lg shadow-primary/30 transition-transform hover:-translate-y-0.5"
            >
              Explore capabilities
              <ArrowRight className="size-4" />
            </Link>
            <Link
              href="/login"
              className="inline-flex items-center gap-2 rounded-full border border-white/15 bg-white/5 px-5 py-2.5 text-sm font-semibold text-foreground transition-colors hover:bg-white/10"
            >
              Sign in
            </Link>
          </div>

          <p className="mt-3 text-sm text-muted-foreground">
            Not subscribed yet?{' '}
            <Link href="/contact" className="font-medium text-primary-bright underline-offset-4 hover:underline">
              Contact us
            </Link>
          </p>

          <ul className="mt-8 flex flex-wrap gap-x-5 gap-y-2 text-xs text-muted-foreground">
            {TRUST.map((t) => (
              <li key={t} className="inline-flex items-center gap-1.5">
                <span aria-hidden className="size-1 rounded-full bg-primary-bright" />
                {t}
              </li>
            ))}
          </ul>
        </div>

        {/* flagship vignette */}
        <Reveal className="[perspective:1200px]" delay={0.15}>
          <Tilt>
            <BrowserFrame url="acme.company.example" className="mx-auto max-w-md">
              <DashboardVignette />
            </BrowserFrame>
          </Tilt>
        </Reveal>
      </div>
    </section>
  );
}
