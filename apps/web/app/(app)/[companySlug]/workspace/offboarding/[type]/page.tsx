import type { Metadata } from 'next';
import { notFound } from 'next/navigation';
import { OFFBOARDING_DOC_TYPES, type OffboardingDocType } from '@/lib/contract';
import { OffboardingDocFill } from '@/components/employee/offboarding-doc-fill';

export const metadata: Metadata = { title: 'Offboarding document' };

export default function WorkspaceOffboardingDocPage({ params }: { params: { type: string } }) {
  const type = params.type as OffboardingDocType;
  if (!OFFBOARDING_DOC_TYPES.includes(type)) notFound();
  return <OffboardingDocFill type={type} />;
}
