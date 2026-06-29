import type { ReactNode } from 'react';

// Tier 2 — employee route group. Public for now (no auth until Phase 5).
// PHASE 5: the employee access guard mounts here.
export default function EmployeeLayout({ children }: { children: ReactNode }) {
  return <>{children}</>;
}
