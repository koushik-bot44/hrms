import * as React from 'react';
import Link from 'next/link';
import { ChevronRight, Home } from 'lucide-react';
import { cn } from '@/lib/utils';

/**
 * Shared breadcrumb (design system): a pill trail with a leading HOME chip, then chevron-separated crumbs.
 * Each crumb is a link (`href`), an action (`onClick`), or plain text (the current page — omit both). The
 * home chip navigates to `homeHref` (default `/`) or calls `onHome`. Colours are all tokens, so it reads in
 * light and dark. Behaviour-only wrapper — no data fetching.
 */
export interface Crumb {
  label: string;
  href?: string;
  onClick?: () => void;
}

export function Breadcrumb({
  items,
  homeHref = '/',
  onHome,
  homeLabel = 'Home',
  className,
}: {
  items: Crumb[];
  homeHref?: string;
  onHome?: () => void;
  homeLabel?: string;
  className?: string;
}) {
  const homeChip = (
    <span className="flex size-6 items-center justify-center rounded-lg bg-surface-tint text-primary">
      <Home className="size-3.5" aria-hidden />
    </span>
  );

  return (
    <nav
      aria-label="Breadcrumb"
      className={cn(
        'flex flex-wrap items-center gap-1.5 rounded-full border bg-card px-2 py-1.5 text-sm',
        className,
      )}
    >
      {onHome ? (
        <button type="button" onClick={onHome} aria-label={homeLabel} className="rounded-lg">
          {homeChip}
        </button>
      ) : (
        <Link href={homeHref} aria-label={homeLabel} className="rounded-lg">
          {homeChip}
        </Link>
      )}
      {items.map((item, i) => {
        const isLast = i === items.length - 1;
        const interactive = !isLast && (item.href || item.onClick);
        return (
          <React.Fragment key={`${item.label}-${i}`}>
            <ChevronRight className="size-4 shrink-0 text-muted-foreground" aria-hidden />
            {interactive ? (
              item.href ? (
                <Link
                  href={item.href}
                  className="rounded px-1 text-muted-foreground transition-colors hover:text-primary"
                >
                  {item.label}
                </Link>
              ) : (
                <button
                  type="button"
                  onClick={item.onClick}
                  className="rounded px-1 text-muted-foreground transition-colors hover:text-primary"
                >
                  {item.label}
                </button>
              )
            ) : (
              <span
                aria-current={isLast ? 'page' : undefined}
                className={cn('px-1', isLast ? 'font-medium text-foreground' : 'text-muted-foreground')}
              >
                {item.label}
              </span>
            )}
          </React.Fragment>
        );
      })}
    </nav>
  );
}
