import { LegacyRedirect } from '@/components/legacy-redirect';

/** Legacy /workspace[/…] → Stage-2 slugged home (transition safety). */
export default function LegacyWorkspaceRedirect() {
  return <LegacyRedirect />;
}
