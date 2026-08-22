import type { Metadata } from 'next';
import { OrganisationCompanies } from '@/components/hierarchy/organisation-companies';

export const metadata: Metadata = { title: 'Organisation' };

/** Organisation — Level 1 (§2): all companies, with staff coverage. Drills to a company, then a team. */
export default function OrganisationPage() {
  return <OrganisationCompanies />;
}
