'use client';

import * as React from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { ClipboardCheck, ExternalLink } from 'lucide-react';
import type { ClearanceFinalStatus, ClearanceView } from '@/lib/contract';
import { getClearance, saveClearance } from '@/lib/api/offboarding-docs';
import { useApiQuery, useApiMutation } from '@/lib/api/hooks';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { surface } from '@/components/ui/surface';
import { cn } from '@/lib/utils';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog';

const FINAL_KEY = 'final_it_signoff';
type ItemState = { value: string | null; remarks: string | null };

/**
 * The HR-side offboarding Clearance checklist (§3.6 stage 2) — the five sections (yes/no + remarks; the IT
 * Asset section is a single "returned"), the final IT sign-off, and the final status. Save upserts and
 * regenerates the PDF. The employee never sees this.
 */
export function OffboardingClearanceDialog({ employeeId }: { employeeId: string }) {
  const queryClient = useQueryClient();
  const key = ['offboarding-clearance', employeeId] as const;
  const [open, setOpen] = React.useState(false);
  const { data } = useApiQuery(key, (s) => getClearance(employeeId, s), { enabled: open });

  const [items, setItems] = React.useState<Record<string, ItemState>>({});
  const [signoff, setSignoff] = React.useState<string | null>(null);
  const [finalStatus, setFinalStatus] = React.useState<ClearanceFinalStatus>('PENDING');

  React.useEffect(() => {
    if (!data) return;
    const next: Record<string, ItemState> = {};
    for (const s of data.sections) {
      for (const it of s.items) next[it.key] = { value: it.value, remarks: it.remarks };
    }
    setItems(next);
    setSignoff(data.finalItSignoff);
    setFinalStatus(data.finalStatus);
  }, [data]);

  const save = useApiMutation(
    () =>
      saveClearance(employeeId, {
        items: Object.fromEntries(Object.entries(items).map(([k, v]) => [k, v])),
        finalItSignoff: signoff,
        finalStatus,
      }),
    {
      successMessage: 'Clearance saved',
      onSuccess: () => void queryClient.invalidateQueries({ queryKey: key }),
    },
  );

  const setItem = (key: string, patch: Partial<ItemState>) =>
    setItems((prev) => ({
      ...prev,
      [key]: { ...(prev[key] ?? { value: null, remarks: null }), ...patch },
    }));

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button type="button" variant="outline" size="sm">
          <ClipboardCheck className="size-4" />
          Clearance
        </Button>
      </DialogTrigger>
      <DialogContent className="max-h-[85vh] max-w-2xl overflow-y-auto">
        <DialogHeader>
          <DialogTitle>Off-boarding clearance</DialogTitle>
          <DialogDescription>
            HR &amp; IT data verification. Saving regenerates the clearance PDF. Not shown to the employee.
          </DialogDescription>
        </DialogHeader>

        {data ? (
          <ClearanceDetails details={data.details} />
        ) : (
          <p className="text-sm text-muted-foreground">Loading…</p>
        )}

        {data?.sections.map((section) => {
          const returned = section.kind === 'RETURNED';
          return (
            <div key={section.key} className="space-y-2">
              <h4 className="text-sm font-semibold">{section.title}</h4>
              <div className="space-y-2">
                {section.items.map((it) => {
                  const st = items[it.key] ?? { value: null, remarks: null };
                  return (
                    <div key={it.key} className={cn(surface('subtle'), 'flex flex-wrap items-center gap-2 p-2.5')}>
                      <span className="min-w-0 flex-1 text-sm">{it.label}</span>
                      <div className="flex items-center gap-1.5">
                        {returned ? (
                          <ToggleButton
                            active={st.value === 'YES'}
                            label="Returned"
                            onClick={() => setItem(it.key, { value: st.value === 'YES' ? null : 'YES' })}
                          />
                        ) : (
                          <>
                            <ToggleButton
                              active={st.value === 'YES'}
                              label="Yes"
                              onClick={() => setItem(it.key, { value: 'YES' })}
                            />
                            <ToggleButton
                              active={st.value === 'NO'}
                              label="No"
                              tone="danger"
                              onClick={() => setItem(it.key, { value: 'NO' })}
                            />
                          </>
                        )}
                      </div>
                      <Input
                        className="h-8 w-full sm:w-40"
                        placeholder="Remarks"
                        value={st.remarks ?? ''}
                        onChange={(e) => setItem(it.key, { remarks: e.target.value })}
                      />
                    </div>
                  );
                })}
              </div>
            </div>
          );
        })}

        <div className="flex flex-wrap items-center gap-2 border-t pt-3">
          <span className="text-sm font-medium">Final IT sign-off:</span>
          <ToggleButton active={signoff === 'YES'} label="Yes" onClick={() => setSignoff('YES')} />
          <ToggleButton active={signoff === 'NO'} label="No" tone="danger" onClick={() => setSignoff('NO')} />
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <span className="text-sm font-medium">Final status:</span>
          {(['APPROVED', 'PENDING', 'ON_HOLD'] as const).map((s) => (
            <ToggleButton key={s} active={finalStatus === s} label={s.replace('_', ' ')} onClick={() => setFinalStatus(s)} />
          ))}
        </div>

        <div className="flex items-center justify-end gap-2 pt-2">
          {data?.downloadUrl ? (
            <a href={data.downloadUrl} target="_blank" rel="noreferrer">
              <Button type="button" variant="outline" size="sm">
                <ExternalLink />
                Open PDF
              </Button>
            </a>
          ) : null}
          <Button type="button" onClick={() => save.mutate()} disabled={save.isPending}>
            {save.isPending ? 'Saving…' : 'Save clearance'}
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

function ClearanceDetails({ details }: { details: ClearanceView['details'] }) {
  const rows: Array<[string, string | null]> = [
    ['Name', details.name],
    ['Employee ID', details.employeeId],
    ['Department', details.department],
    ['Designation', details.designation],
    ['Manager', details.manager],
    ['Last working day', details.lastWorkingDay],
  ];
  return (
    <dl className="grid grid-cols-2 gap-x-4 gap-y-1 text-sm sm:grid-cols-3">
      {rows.map(([k, v]) => (
        <div key={k}>
          <dt className="text-xs text-muted-foreground">{k}</dt>
          <dd className="truncate">{v ?? '—'}</dd>
        </div>
      ))}
    </dl>
  );
}

function ToggleButton({
  active,
  label,
  onClick,
  tone = 'success',
}: {
  active: boolean;
  label: string;
  onClick: () => void;
  tone?: 'success' | 'danger';
}) {
  return (
    <Button
      type="button"
      size="sm"
      variant={active ? (tone === 'danger' ? 'destructive' : 'success') : 'outline'}
      onClick={onClick}
    >
      {label}
    </Button>
  );
}
