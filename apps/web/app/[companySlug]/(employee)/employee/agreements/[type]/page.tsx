import type { Metadata } from 'next';
import { notFound } from 'next/navigation';
import { AgreementTypeValues, type AgreementType } from '@/lib/contract';
import { AgreementFill } from '@/components/employee/agreement-fill';

export const metadata: Metadata = { title: 'Agreement' };

export default function EmployeeAgreementPage({ params }: { params: { type: string } }) {
  const type = params.type as AgreementType;
  if (!AgreementTypeValues.includes(type)) notFound();
  return <AgreementFill type={type} />;
}
