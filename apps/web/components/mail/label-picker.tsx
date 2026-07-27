'use client';

import * as React from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Plus } from 'lucide-react';
import type { MailLabelRef, ThreadDetail, ThreadPage } from '@/lib/contract';
import { useApiMutation, useApiQuery } from '@/lib/api/hooks';
import { applyLabel, createLabel, getLabels, mailKeys, removeLabel } from '@/lib/api/mail';
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { cn } from '@/lib/utils';

const byName = (a: MailLabelRef, b: MailLabelRef) =>
  a.name.localeCompare(b.name, undefined, { sensitivity: 'base' });

/** The viewer's labels on a thread, shown as small chips. */
export function LabelChips({ labels, className }: { labels: MailLabelRef[]; className?: string }) {
  if (!labels || labels.length === 0) return null;
  return (
    <>
      {labels.map((l) => (
        <span
          key={l.id}
          className={cn(
            'inline-flex max-w-[10rem] items-center truncate rounded bg-surface-tint px-1.5 py-0.5 text-[10px] font-medium text-primary',
            className,
          )}
        >
          {l.name}
        </span>
      ))}
    </>
  );
}

/**
 * A dropdown to tag/untag a thread with the viewer's labels (a checklist — checked = applied), plus a
 * "New label" action. Apply/remove are optimistic (the chip appears/vanishes immediately; the label views
 * + counts reconcile on settle). {@code children} is the trigger button.
 */
export function LabelPicker({
  threadId,
  labels,
  children,
}: {
  threadId: string;
  labels: MailLabelRef[];
  children: React.ReactNode;
}) {
  const queryClient = useQueryClient();
  const labelsQuery = useApiQuery(mailKeys.labels, getLabels);
  const applied = new Set(labels.map((l) => l.id));

  const setThreadLabels = React.useCallback(
    (next: MailLabelRef[]) => {
      queryClient.setQueriesData<ThreadPage>({ queryKey: ['mail'] }, (old) => {
        if (!old || !Array.isArray(old.content)) return old;
        if (!old.content.some((r) => r.threadId === threadId)) return old;
        return {
          ...old,
          content: old.content.map((r) => (r.threadId === threadId ? { ...r, labels: next } : r)),
        };
      });
      queryClient.setQueryData<ThreadDetail>(mailKeys.thread(threadId), (old) =>
        old ? { ...old, labels: next } : old,
      );
    },
    [queryClient, threadId],
  );

  const reconcile = () => {
    void queryClient.invalidateQueries({ queryKey: ['mail', 'label-threads'] });
    void queryClient.invalidateQueries({ queryKey: mailKeys.labels });
  };

  const toggle = useApiMutation(
    (v: { labelId: string; name: string; apply: boolean }) =>
      v.apply ? applyLabel(threadId, v.labelId) : removeLabel(threadId, v.labelId),
    {
      onMutate: (v) => {
        const next = v.apply
          ? [...labels, { id: v.labelId, name: v.name }].sort(byName)
          : labels.filter((l) => l.id !== v.labelId);
        setThreadLabels(next);
      },
      onError: () => setThreadLabels(labels), // revert
      onSettled: reconcile,
    },
  );

  const createAndApply = useApiMutation(
    async () => {
      const name = window.prompt('New label name')?.trim();
      if (!name) return null;
      const label = await createLabel(name);
      await applyLabel(threadId, label.id);
      return { id: label.id, name: label.name };
    },
    {
      onSuccess: (label) => {
        if (!label) return;
        setThreadLabels([...labels, label].sort(byName));
        reconcile();
        toast.success(`Labeled “${label.name}”`);
      },
    },
  );

  const options = labelsQuery.data ?? [];

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>{children}</DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-56">
        <DropdownMenuLabel>Label as</DropdownMenuLabel>
        {options.map((l) => (
          <DropdownMenuCheckboxItem
            key={l.id}
            checked={applied.has(l.id)}
            onCheckedChange={(c) => toggle.mutate({ labelId: l.id, name: l.name, apply: Boolean(c) })}
            onSelect={(e) => e.preventDefault()}
          >
            <span className="truncate">{l.name}</span>
          </DropdownMenuCheckboxItem>
        ))}
        {options.length === 0 ? (
          <DropdownMenuItem disabled>No labels yet</DropdownMenuItem>
        ) : null}
        <DropdownMenuSeparator />
        <DropdownMenuItem onSelect={() => createAndApply.mutate()}>
          <Plus className="size-4" />
          New label…
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
