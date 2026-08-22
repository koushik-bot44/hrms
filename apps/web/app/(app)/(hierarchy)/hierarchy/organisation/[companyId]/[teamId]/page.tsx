import type { Metadata } from 'next';
import { OrganisationTeam } from '@/components/hierarchy/organisation-team';

export const metadata: Metadata = { title: 'Organisation — Team' };

/** Organisation — Level 3 (§2 charter widening): a team's people (names/codes/designations/roles only). */
export default function OrganisationTeamPage({
  params,
}: {
  params: { companyId: string; teamId: string };
}) {
  return <OrganisationTeam companyId={params.companyId} teamId={params.teamId} />;
}
