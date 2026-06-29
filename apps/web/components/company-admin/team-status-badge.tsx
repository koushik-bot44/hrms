import { Badge } from '@/components/ui/badge';

/** A team is "complete" once it has both an HR and a Manager (§2). */
export function TeamStatusBadge({ complete }: { complete: boolean }) {
  return (
    <Badge variant={complete ? 'success' : 'warning'}>
      <span className="size-1.5 rounded-full bg-current" aria-hidden />
      {complete ? 'Complete' : 'Needs setup'}
    </Badge>
  );
}
