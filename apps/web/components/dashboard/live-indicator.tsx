import * as React from 'react';
import { cn } from '@/lib/utils';

/**
 * A tiny "this screen is live" indicator (green dot + label). Use it ONLY on screens that actually poll
 * (react-query `refetchInterval`) so the pulse is truthful — never decoration on a static page. The pulse
 * respects prefers-reduced-motion (globals.css neutralises the ping). Tokens only.
 */
export function LiveIndicator({
  label = 'Live — updates automatically',
  className,
}: {
  label?: string;
  className?: string;
}) {
  return (
    <span
      role="status"
      className={cn('inline-flex items-center gap-1.5 text-xs text-muted-foreground', className)}
    >
      <span className="relative flex size-2" aria-hidden>
        <span className="absolute inline-flex size-full animate-ping rounded-full bg-success/60" />
        <span className="relative inline-flex size-2 rounded-full bg-success" />
      </span>
      {label}
    </span>
  );
}
