import type { Metadata } from 'next';
import { Users } from 'lucide-react';
import { PageHeader } from '@/components/page-header';
import { EmptyState } from '@/components/empty-state';
import { Button } from '@/components/ui/button';
import { Plus } from 'lucide-react';

export const metadata: Metadata = { title: 'Teams' };

export default function TeamsPage() {
  return (
    <div className="space-y-6">
      <PageHeader
        title="Teams"
        description="Create teams and assign one HR and one Manager to each."
        actions={
          <Button size="sm">
            <Plus />
            New team
          </Button>
        }
      />
      <EmptyState
        icon={Users}
        title="No teams yet"
        description="Create a team and assign its HR and Manager to begin onboarding."
        action={
          <Button size="sm">
            <Plus />
            New team
          </Button>
        }
      />
    </div>
  );
}
