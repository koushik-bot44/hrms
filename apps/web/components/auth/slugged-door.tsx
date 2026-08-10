'use client';

import * as React from 'react';
import { notFound } from 'next/navigation';
import { ShieldCheck } from 'lucide-react';

/**
 * Wraps a slugged sign-in door (`/{slug}/login`, `/{slug}/employee/login`). It confirms the company exists via
 * the PUBLIC `/public/companies/{slug}` lookup — a bad/archived slug → Next `notFound()` (404) — and passes the
 * company NAME to the door for context. Unauthenticated by design (the `[companySlug]` guard carves these paths
 * out); a bare `fetch` to the same base the app client uses, no auth/session. Name-only disclosure — the
 * inherent reveal of any per-company login URL.
 */
const API_BASE = (process.env.NEXT_PUBLIC_API_URL ?? '').replace(/\/+$/, '');

export function SluggedDoor({
  slug,
  children,
}: {
  slug: string;
  children: (companyName: string) => React.ReactNode;
}) {
  const [state, setState] = React.useState<'loading' | 'ok' | 'notfound'>('loading');
  const [name, setName] = React.useState('');

  React.useEffect(() => {
    let alive = true;
    fetch(`${API_BASE}/public/companies/${encodeURIComponent(slug)}`)
      .then((r) => (r.ok ? r.json() : Promise.reject(r.status)))
      .then((data: { name?: string }) => {
        if (alive) {
          setName(data?.name ?? '');
          setState('ok');
        }
      })
      .catch(() => {
        if (alive) setState('notfound');
      });
    return () => {
      alive = false;
    };
  }, [slug]);

  if (state === 'notfound') notFound();
  if (state === 'loading') {
    return (
      <div className="flex min-h-dvh items-center justify-center" role="status" aria-busy="true">
        <div className="flex items-center gap-2 text-muted-foreground">
          <ShieldCheck className="size-4 animate-pulse" />
          <span className="text-sm">Loading…</span>
        </div>
      </div>
    );
  }
  return <>{children(name)}</>;
}
