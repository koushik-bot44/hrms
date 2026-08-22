import type { Metadata } from 'next';
import { OrganisationCompany } from '@/components/hierarchy/organisation-company';

export const metadata: Metadata = { title: 'Organisation — Company' };

/** Organisation — Level 2 (§2): one company's teams + assigned staff. Opaque companyId in the URL. */
export default function OrganisationCompanyPage({ params }: { params: { companyId: string } }) {
  return <OrganisationCompany companyId={params.companyId} />;
}
