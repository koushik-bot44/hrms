import type { Metadata } from 'next';
import { AccountantRecord } from '@/components/accountant/accountant-record';

export const metadata: Metadata = { title: 'Employee record' };

export default function AccountantEmployeeRecordPage({ params }: { params: { id: string } }) {
  return (
    <div className="space-y-6">
      <AccountantRecord employeeId={params.id} />
    </div>
  );
}
