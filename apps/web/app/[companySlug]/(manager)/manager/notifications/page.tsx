import type { Metadata } from 'next';
import { PageHeader } from '@/components/page-header';
import { NotificationsFeed } from '@/components/manager/notifications-feed';

export const metadata: Metadata = { title: 'Notifications' };

export default function ManagerNotificationsPage() {
  return (
    <div className="space-y-6">
      <PageHeader
        title="Notifications"
        description="Who was onboarded, who was verified, and what needs your attention."
      />
      <NotificationsFeed />
    </div>
  );
}
