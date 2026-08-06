import type { Metadata } from 'next';
import { AccountantAuditScreen } from '@/components/accountant/screens';

export const metadata: Metadata = { title: 'Approval audit' };

export default function AccountsAuditPage() {
  return <AccountantAuditScreen />;
}
