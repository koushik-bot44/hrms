import * as React from 'react';
import { cn } from '@/lib/utils';

/**
 * Static presentational chrome for the marketing page (no hooks → negligible client JS). Section shells,
 * editorial headings, the glass browser-frame that wraps every stylized in-code vignette, and small glass
 * bits. All colour comes from the `.marketing` tokens (globals.css) — tokens-only, no literals.
 */

/** A section wrapper with an anchor id (for the sticky nav) and consistent vertical rhythm. */
export function Section({
  id,
  children,
  className,
}: {
  id?: string;
  children: React.ReactNode;
  className?: string;
}) {
  return (
    <section id={id} className={cn('relative scroll-mt-20 px-5 py-20 sm:px-8 md:py-28', className)}>
      <div className="mx-auto w-full max-w-6xl">{children}</div>
    </section>
  );
}

/** A small uppercase indigo eyebrow with a leading dot — the section kicker. */
export function Eyebrow({ children, className }: { children: React.ReactNode; className?: string }) {
  return (
    <span
      className={cn(
        'inline-flex items-center gap-2 text-xs font-semibold uppercase tracking-[0.18em] text-primary-bright',
        className,
      )}
    >
      <span aria-hidden className="size-1.5 rounded-full bg-primary-bright shadow-[0_0_12px] shadow-primary-bright/70" />
      {children}
    </span>
  );
}

/** An editorial section heading; `accent` clips a gradient across the accented span. */
export function SectionHeading({
  children,
  className,
  as: Tag = 'h2',
}: {
  children: React.ReactNode;
  className?: string;
  as?: 'h1' | 'h2' | 'h3';
}) {
  return (
    <Tag
      className={cn(
        'text-balance font-semibold tracking-tight text-foreground',
        Tag === 'h2' ? 'text-3xl sm:text-4xl md:text-[2.75rem] md:leading-[1.08]' : 'text-2xl sm:text-3xl',
        className,
      )}
    >
      {children}
    </Tag>
  );
}

/** A lead paragraph under a heading. */
export function Lead({ children, className }: { children: React.ReactNode; className?: string }) {
  return (
    <p className={cn('text-pretty text-base leading-relaxed text-muted-foreground sm:text-lg', className)}>
      {children}
    </p>
  );
}

/**
 * A glass "browser window" that frames a stylized vignette so it reads as an intentional illustration of the
 * real UI (NOT a screenshot). Traffic-light dots + a generic placeholder address (never a real host).
 */
export function BrowserFrame({
  children,
  url = 'app.company.example',
  className,
  bodyClassName,
}: {
  children: React.ReactNode;
  url?: string;
  className?: string;
  bodyClassName?: string;
}) {
  return (
    <div
      className={cn(
        'm-glass overflow-hidden rounded-xl shadow-2xl shadow-black/40 ring-1 ring-inset ring-white/5',
        className,
      )}
    >
      <div className="flex items-center gap-2 border-b border-white/10 bg-white/[0.03] px-3.5 py-2.5">
        <span aria-hidden className="flex gap-1.5">
          <span className="size-2.5 rounded-full bg-white/25" />
          <span className="size-2.5 rounded-full bg-white/20" />
          <span className="size-2.5 rounded-full bg-white/15" />
        </span>
        <div className="ml-2 flex-1">
          <div className="mx-auto flex w-full max-w-[240px] items-center justify-center gap-1.5 rounded-md bg-black/25 px-3 py-1 text-[11px] text-muted-foreground ring-1 ring-inset ring-white/5">
            <LockGlyph />
            <span className="truncate">{url}</span>
          </div>
        </div>
      </div>
      <div className={cn('p-4 sm:p-5', bodyClassName)}>{children}</div>
    </div>
  );
}

function LockGlyph() {
  return (
    <svg aria-hidden viewBox="0 0 24 24" className="size-3 shrink-0 opacity-70" fill="none" stroke="currentColor" strokeWidth={2}>
      <rect x="4" y="10" width="16" height="10" rx="2" />
      <path d="M8 10V7a4 4 0 0 1 8 0v3" />
    </svg>
  );
}

/** A generic soft glass card on the dark surface. */
export function GlassCard({
  children,
  className,
}: {
  children: React.ReactNode;
  className?: string;
}) {
  return (
    <div
      className={cn(
        'rounded-2xl border border-white/10 bg-white/[0.02] p-5 transition-colors duration-300 hover:border-primary/40 hover:bg-white/[0.04]',
        className,
      )}
    >
      {children}
    </div>
  );
}
