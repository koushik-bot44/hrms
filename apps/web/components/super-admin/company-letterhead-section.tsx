'use client';

import * as React from 'react';
import { FileImage, Trash2, Upload } from 'lucide-react';
import { toast } from 'sonner';
import type { LetterheadPart, LetterheadPartName } from '@/lib/contract';
import { getLetterhead, removeLetterhead, uploadLetterhead } from '@/lib/api/companies';
import { useApiQuery } from '@/lib/api/hooks';
import { ApiError } from '@/lib/api/client';
import { LoadingSkeleton } from '@/components/loading-skeleton';
import { EmptyState } from '@/components/empty-state';
import { Button } from '@/components/ui/button';

/**
 * SUPER_ADMIN letterhead management for one company (§3.5): a HEADER and/or FOOTER band image (PNG/JPG, full
 * page width, ≤5 MB, ≥1000px wide), each replaceable/removable. It applies to documents generated FROM NOW ON
 * (offer letters, agreements, offboarding documents + letters); already-generated PDFs are unchanged, and the
 * onboarding forms are never branded.
 */
export function CompanyLetterheadSection({ companyId }: { companyId: string }) {
  const query = useApiQuery(['company-letterhead', companyId], (signal) => getLetterhead(companyId, signal));

  return (
    <section className="space-y-3">
      <div>
        <h2 className="text-sm font-semibold text-muted-foreground">Letterhead</h2>
        <p className="text-xs text-muted-foreground">
          A header and/or footer band (PNG or JPG, full page width, ≤5&nbsp;MB, ≥1000px wide) printed on every
          page of generated documents — offer letters, agreements, offboarding documents and letters. It applies
          to documents generated <strong>from now on</strong>; existing PDFs are unchanged, and the onboarding
          forms are never branded.
        </p>
      </div>

      {query.isLoading ? (
        <LoadingSkeleton lines={4} />
      ) : query.isError ? (
        <EmptyState
          icon={FileImage}
          title="Couldn't load the letterhead"
          description={query.error?.message ?? 'Please try again.'}
        />
      ) : (
        <div className="grid gap-4 sm:grid-cols-2">
          <PartCard
            companyId={companyId}
            part="header"
            label="Header band"
            data={query.data?.header ?? null}
            onChanged={() => query.refetch()}
          />
          <PartCard
            companyId={companyId}
            part="footer"
            label="Footer band"
            data={query.data?.footer ?? null}
            onChanged={() => query.refetch()}
          />
        </div>
      )}
    </section>
  );
}

function PartCard({
  companyId,
  part,
  label,
  data,
  onChanged,
}: {
  companyId: string;
  part: LetterheadPartName;
  label: string;
  data: LetterheadPart | null;
  onChanged: () => void;
}) {
  const [busy, setBusy] = React.useState(false);
  const inputRef = React.useRef<HTMLInputElement>(null);

  async function onPick(event: React.ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    event.target.value = ''; // let the same file be re-picked after an error
    if (!file) return;
    setBusy(true);
    try {
      await uploadLetterhead(companyId, part, file);
      toast.success(`${label} updated — applies to documents generated from now on.`);
      onChanged();
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Upload failed');
    } finally {
      setBusy(false);
    }
  }

  async function onRemove() {
    setBusy(true);
    try {
      await removeLetterhead(companyId, part);
      toast.success(`${label} removed — that band renders plain again.`);
      onChanged();
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Remove failed');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="space-y-3 rounded-2xl border bg-card p-4 shadow-card">
      <div className="flex items-center justify-between">
        <span className="text-sm font-medium">{label}</span>
        {data ? (
          <span className="text-xs text-muted-foreground tabular-nums">
            {data.width}×{data.height}px
          </span>
        ) : null}
      </div>

      <div className="flex h-28 items-center justify-center overflow-hidden rounded-lg border bg-muted/30">
        {data?.previewUrl ? (
          // eslint-disable-next-line @next/next/no-img-element -- presigned URL / arbitrary image, not a static asset
          <img src={data.previewUrl} alt={`${label} preview`} className="max-h-full max-w-full object-contain" />
        ) : (
          <span className="text-xs text-muted-foreground">None — documents render plain</span>
        )}
      </div>

      <div className="flex items-center gap-2">
        <input
          ref={inputRef}
          type="file"
          accept="image/png,image/jpeg"
          className="hidden"
          onChange={onPick}
        />
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={busy}
          onClick={() => inputRef.current?.click()}
        >
          <Upload className="size-4" />
          {busy ? 'Working…' : data ? 'Replace' : 'Upload'}
        </Button>
        {data ? (
          <Button type="button" variant="outline" size="sm" disabled={busy} onClick={onRemove}>
            <Trash2 className="size-4" />
            Remove
          </Button>
        ) : null}
      </div>
    </div>
  );
}
