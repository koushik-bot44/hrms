import { LegacyRedirect } from '@/components/legacy-redirect';

/** Legacy /manager[/…] → Stage-2 slugged home (transition safety). */
export default function LegacyManagerRedirect() {
  return <LegacyRedirect />;
}
