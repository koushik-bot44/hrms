import type { CompanyStatus } from '@ihrms/shared';
import { Badge } from '@/components/ui/badge';

const META: Record<CompanyStatus, { label: string; variant: 'success' | 'warning' }> = {
  ACTIVE: { label: 'Active', variant: 'success' },
  SUSPENDED: { label: 'Suspended', variant: 'warning' },
};

/** Status badge for a company (ACTIVE/SUSPENDED — a free-string column, not a domain enum). */
export function CompanyStatusBadge({ status }: { status: CompanyStatus }) {
  const meta = META[status] ?? META.ACTIVE;
  return (
    <Badge variant={meta.variant}>
      <span className="size-1.5 rounded-full bg-current" aria-hidden />
      {meta.label}
    </Badge>
  );
}
