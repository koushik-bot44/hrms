import { UserRole, type Session } from '../contract';

/**
 * Whether a signed-in principal can use internal mail (§8) — the UI mirror of the backend send-graph
 * (`AuthorizationService.canSendMail`). Every STAFF role can, EXCEPT **HIERARCHY**: it has no mail edge
 * (null companyId, not in any platform pair), so it can neither send nor receive and its mailbox is always
 * empty — the entry point must be hidden for it. A credentialed EMPLOYEE can once it has a mailbox address;
 * an OTP-only onboarding employee cannot. Pure (no hooks) so it's unit-testable.
 */
export function canUseMail(session: Session | null | undefined): boolean {
  if (!session) return false;
  if (session.type === 'USER') return session.role !== UserRole.HIERARCHY;
  return Boolean(session.mailAddress); // credentialed employee only
}
