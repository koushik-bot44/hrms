import { LegacyRedirect } from '@/components/legacy-redirect';

/** Legacy /hr[/…] → Stage-2 slugged home (transition safety). */
export default function LegacyHrRedirect() {
  return <LegacyRedirect />;
}
