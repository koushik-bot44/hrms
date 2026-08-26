'use client';

import { useSearchParams } from 'next/navigation';
import { ConsoleShell } from './console-shell';
import { TaOverview } from './ta-overview';
import { LiveBoard } from './live-board';
import { PeopleRoster } from './people-roster';
import { DevicesPanel } from './devices-panel';
import { PersonDayView } from './person-day-view';

/**
 * The console's four section screens, each a thin binding of {@link ConsoleShell} to one body.
 *
 * They live together because each is a title, a sentence and one component — splitting them across
 * four files would put more ceremony on the page than content. The bodies themselves are separate
 * modules, which is where the actual size is.
 */

export function OverviewScreen() {
  return (
    <ConsoleShell
      title="Time & Attendance"
      description="Biometric attendance across the site — who is in, which terminals are healthy, and what still needs a decision."
    >
      {(siteId) => <TaOverview siteId={siteId} />}
    </ConsoleShell>
  );
}

export function LiveScreen() {
  return (
    <ConsoleShell
      title="Live board"
      description="Where everyone is right now, from their most recent punch. Refreshes on its own."
    >
      {(siteId) => <LiveBoard siteId={siteId} />}
    </ConsoleShell>
  );
}

export function PeopleScreen() {
  return (
    <ConsoleShell
      title="People"
      description="The biometric roster — the sole source of pin resolution. An IHRMS employee link is enrichment, not a requirement."
    >
      {(siteId) => <PeopleRoster siteId={siteId} />}
    </ConsoleShell>
  );
}

export function DevicesScreen() {
  return (
    <ConsoleShell
      title="Terminals"
      description="Every device that has contacted the server. Claiming one binds it to a site and starts attributing its punches."
    >
      {(_siteId, site) => <DevicesPanel site={site} />}
    </ConsoleShell>
  );
}

/** The day view is addressed by person, so it owns its own scope rather than going through the shell. */
export function PersonDayScreen({ personId }: { personId: string }) {
  const site = useSearchParams().get('site');
  return <PersonDayView personId={personId} siteId={site} />;
}
