import type { Metadata } from 'next';
import { Bell } from 'lucide-react';
import { PageHeader } from '@/components/page-header';
import { EmptyState } from '@/components/empty-state';

export const metadata: Metadata = { title: 'Notifications' };

export default function ManagerNotificationsPage() {
  return (
    <div className="space-y-6">
      <PageHeader
        title="Notifications"
        description="Who was onboarded, who was verified, and what needs your attention."
      />
      <EmptyState
        icon={Bell}
        title="You're all caught up"
        description="Onboarding and verification updates for your team will appear here."
      />
    </div>
  );
}
