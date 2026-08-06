import type { Metadata } from 'next';
import { AccountantRecordScreen } from '@/components/accountant/screens';

export const metadata: Metadata = { title: 'Employee record' };

export default function AccountantEmployeeRecordPage({ params }: { params: { id: string } }) {
  return <AccountantRecordScreen employeeId={params.id} />;
}
