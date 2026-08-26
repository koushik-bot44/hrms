'use client';

import * as React from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { CheckCircle2, FileUp, TriangleAlert } from 'lucide-react';
import { importRoster, iclockKeys, type RosterImportReport } from '@/lib/api/iclock';
import { useApiMutation } from '@/lib/api/hooks';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { surface } from '@/components/ui/surface';
import { cn } from '@/lib/utils';

/**
 * Roster import: paste or drop a CSV, read the dry run, then commit.
 *
 * The dry run is NOT optional and is not a preview toggle — the API is dry-run by default and this
 * dialog cannot reach `commit=true` without having rendered the report first. A roster import decides
 * who every punch in the building belongs to; committing one unseen is how 206 people silently get
 * filed under the wrong company.
 *
 * The report is shown in the shape the decision needs: per-company counts (is this the split I
 * expect?), company-less rows listed individually rather than counted (each one is an operator
 * decision), and unmatched company labels (a company exists on the terminals but not in IHRMS).
 */
export function RosterImportDialog({
  siteId,
  open,
  onOpenChange,
}: {
  siteId: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const qc = useQueryClient();
  const [csv, setCsv] = React.useState('');
  const [report, setReport] = React.useState<RosterImportReport | null>(null);
  const [dragging, setDragging] = React.useState(false);

  const reset = () => {
    setCsv('');
    setReport(null);
    setDragging(false);
  };

  const dryRun = useApiMutation((text: string) => importRoster(siteId, text, false), {
    onSuccess: (r) => setReport(r),
    errorMessage: 'Could not read that CSV.',
  });

  const commit = useApiMutation((text: string) => importRoster(siteId, text, true), {
    successMessage: (r) => `Imported ${r.created} new and updated ${r.updated} people.`,
    onSuccess: () => {
      // The import changes who every unattributed punch belongs to, so invalidate the whole site.
      qc.invalidateQueries({ queryKey: iclockKeys.site(siteId) });
      onOpenChange(false);
      reset();
    },
  });

  const onDrop = async (e: React.DragEvent) => {
    e.preventDefault();
    setDragging(false);
    const file = e.dataTransfer.files?.[0];
    if (file) setCsv(await file.text());
  };

  const onPick = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (file) setCsv(await file.text());
  };

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        onOpenChange(next);
        if (!next) reset();
      }}
    >
      <DialogContent className="max-h-[85vh] max-w-3xl overflow-y-auto">
        <DialogHeader>
          <DialogTitle>Import roster</DialogTitle>
          <DialogDescription>
            Columns: pin, pin_canonical, name, email, company, team, role, late_exempt_min, deleted,
            source — plus an optional excluded_from_reports. Nothing is written until you commit.
          </DialogDescription>
        </DialogHeader>

        {!report ? (
          <div className="space-y-4">
            <div
              onDragOver={(e) => {
                e.preventDefault();
                setDragging(true);
              }}
              onDragLeave={() => setDragging(false)}
              onDrop={onDrop}
              className={cn(
                'rounded-xl border border-dashed px-6 py-8 text-center transition-colors',
                dragging ? 'border-primary bg-primary/5' : 'border-border bg-muted/40',
              )}
            >
              <FileUp className="mx-auto mb-3 size-6 text-muted-foreground" />
              <p className="text-sm text-muted-foreground">
                Drop a CSV here, or{' '}
                <label className="cursor-pointer font-medium text-primary underline-offset-4 hover:underline">
                  choose a file
                  <input type="file" accept=".csv,text/csv" className="sr-only" onChange={onPick} />
                </label>
              </p>
            </div>

            <textarea
              value={csv}
              onChange={(e) => setCsv(e.target.value)}
              rows={8}
              spellCheck={false}
              placeholder="…or paste the CSV here"
              className="w-full rounded-md border border-input bg-background p-3 font-mono text-xs ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
            />

            <div className="flex justify-end gap-2">
              <Button variant="ghost" onClick={() => onOpenChange(false)}>
                Cancel
              </Button>
              <Button
                onClick={() => dryRun.mutate(csv)}
                disabled={!csv.trim() || dryRun.isPending}
              >
                {dryRun.isPending ? 'Checking…' : 'Preview changes'}
              </Button>
            </div>
          </div>
        ) : (
          <div className="space-y-5">
            <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
              {[
                { label: 'Rows', value: report.total },
                { label: 'New people', value: report.created },
                { label: 'Updated', value: report.updated },
                { label: 'Skipped', value: report.skipped },
              ].map((s) => (
                <div key={s.label} className={cn(surface('subtle'), 'px-3 py-2.5')}>
                  <div className="text-xs text-muted-foreground">{s.label}</div>
                  <div className="text-xl font-semibold tabular-nums">{s.value}</div>
                </div>
              ))}
            </div>

            <section>
              <h3 className="mb-2 text-sm font-semibold">By company</h3>
              <ul className="divide-y divide-border rounded-xl border border-border">
                {report.byCompany.map((c) => (
                  <li
                    key={`${c.companyId ?? 'none'}-${c.seedLabel ?? ''}`}
                    className="flex items-center justify-between gap-3 px-3 py-2 text-sm"
                  >
                    <span className="min-w-0 truncate">
                      {c.companyName ?? (
                        <span className="text-muted-foreground">
                          {c.seedLabel} — no matching company in IHRMS
                        </span>
                      )}
                    </span>
                    <span className="shrink-0 tabular-nums text-muted-foreground">{c.people}</span>
                  </li>
                ))}
              </ul>
            </section>

            {report.companyless.length > 0 ? (
              <section>
                <h3 className="mb-2 inline-flex items-center gap-2 text-sm font-semibold">
                  <TriangleAlert className="size-4 text-warning" />
                  No company at all ({report.companyless.length})
                </h3>
                <p className="mb-2 text-xs text-muted-foreground">
                  These people resolve and punch normally, but nothing scopes them to a company. Each
                  one needs a decision after the import.
                </p>
                <ul className="max-h-48 space-y-1 overflow-y-auto rounded-xl border border-border p-2 text-sm">
                  {report.companyless.map((r) => (
                    <li key={r.pin} className="flex justify-between gap-3 px-1 py-0.5">
                      <span className="truncate">{r.name ?? 'Unnamed'}</span>
                      <span className="shrink-0 font-mono text-xs text-muted-foreground">
                        {r.pin}
                      </span>
                    </li>
                  ))}
                </ul>
              </section>
            ) : null}

            {report.unmatchedCompanyLabels.length > 0 ? (
              <section>
                <h3 className="mb-2 text-sm font-semibold">
                  Company labels with no IHRMS match ({report.unmatchedCompanyLabels.length})
                </h3>
                <div className="flex flex-wrap gap-1.5">
                  {report.unmatchedCompanyLabels.map((l) => (
                    <span
                      key={l}
                      className="rounded-full border border-border px-2.5 py-0.5 text-xs text-muted-foreground"
                    >
                      {l}
                    </span>
                  ))}
                </div>
              </section>
            ) : null}

            {report.duplicateEmails.length > 0 ? (
              <section>
                <h3 className="mb-2 text-sm font-semibold">
                  Shared email addresses ({report.duplicateEmails.length})
                </h3>
                <p className="text-xs text-muted-foreground">
                  Flagged, not blocked — {report.duplicateEmails.join(', ')}
                </p>
              </section>
            ) : null}

            <div className="flex flex-wrap justify-end gap-2 border-t border-border pt-4">
              <Button variant="ghost" onClick={() => setReport(null)}>
                Back
              </Button>
              <Button onClick={() => commit.mutate(csv)} disabled={commit.isPending}>
                <CheckCircle2 className="size-4" />
                {commit.isPending
                  ? 'Importing…'
                  : `Import ${report.created} new, update ${report.updated}`}
              </Button>
            </div>
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}
