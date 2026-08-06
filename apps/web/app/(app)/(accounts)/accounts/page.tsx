import type { Metadata } from 'next';
import { AccountantOverviewScreen } from '@/components/accountant/screens';

export const metadata: Metadata = { title: 'Overview' };

export default function AccountsOverviewPage() {
  return <AccountantOverviewScreen />;
}
