import type { ReactNode } from 'react';

// Tier 0 — anonymous/public route group. No auth (and none planned: this tier is
// intentionally public). The group boundary keeps public routes grouped so shared
// public chrome can mount here later.
export default function PublicLayout({ children }: { children: ReactNode }) {
  return <>{children}</>;
}
