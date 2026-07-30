import type { Metadata } from 'next';
import { AccountantOverviewScreen } from '@/components/accountant/screens';

export const metadata: Metadata = { title: 'Overview' };

export default function AccountantOverviewPage() {
  return <AccountantOverviewScreen />;
}
