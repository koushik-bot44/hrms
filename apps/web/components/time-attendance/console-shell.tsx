'use client';

import * as React from 'react';
import { MapPinOff } from 'lucide-react';
import type { IclockSite } from '@/lib/api/iclock';
import { PageHeader } from '@/components/page-header';
import { EmptyState } from '@/components/empty-state';
import { LoadingSkeleton } from '@/components/loading-skeleton';
import { SectionTabs } from './section-tabs';
import { SitePicker } from './site-picker';
import { ConsoleError } from './console-error';
import { useSiteScope } from './use-site-scope';

/**
 * Common chrome for every console section: heading, site picker, section bar.
 *
 * The scope is resolved once, here, and handed down — so a section body never has to cope with "no
 * site chosen yet" and can be written against a real `siteId`. The three states that precede that
 * (loading, unreachable, no sites at all) are handled once rather than in five places, and the
 * no-sites case in particular is a genuine day-one state, not an error.
 */
export function ConsoleShell({
  title,
  description,
  actions,
  children,
}: {
  title: string;
  description: string;
  actions?: React.ReactNode;
  children: (siteId: string, site: IclockSite | null) => React.ReactNode;
}) {
  const scope = useSiteScope();

  return (
    <div className="space-y-6">
      <PageHeader
        title={title}
        description={description}
        actions={
          <>
            <SitePicker sites={scope.sites} siteId={scope.siteId} onChange={scope.setSiteId} />
            {actions}
          </>
        }
      />

      <SectionTabs />

      {scope.isLoading ? (
        <LoadingSkeleton lines={6} />
      ) : scope.isError ? (
        <ConsoleError error={scope.error} />
      ) : scope.isEmpty || !scope.siteId ? (
        // EMPTY STATE — no site exists. Everything else in the console is scoped to one, so there is
        // nothing truthful to render until a site is created; saying so beats five blank screens.
        <EmptyState
          icon={MapPinOff}
          title="No site yet"
          description="A site is the building the terminals live in — it groups the companies working there. One has to exist before attendance can be scoped to anything."
        />
      ) : (
        children(scope.siteId, scope.site)
      )}
    </div>
  );
}
