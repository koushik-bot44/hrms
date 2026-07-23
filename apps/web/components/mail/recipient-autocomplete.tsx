'use client';

import * as React from 'react';
import { X } from 'lucide-react';
import type { MailParty } from '@/lib/contract';
import { cn } from '@/lib/utils';
import { surface } from '@/components/ui/surface';

const MAX_SHOWN = 8;

/**
 * A type-to-search recipient chip input (§8 redesign). The dropdown stays hidden while the field is
 * empty (no pre-populated contact list); it opens once the user types ≥1 character, filtering the
 * ALLOWED contacts (the fetched `/mail/contacts` set) by mail ADDRESS only (rows still show name +
 * address; typing part of a name does not surface a contact). A string that matches no
 * allowed contact shows a non-selectable "No matches" state — never an add-anything option — so only
 * graph-permitted people can ever become recipients. Selected people show as removable chips.
 * Keyboard: ↑/↓ to move, Enter to add, Esc to close, Backspace (empty) removes the last chip.
 */
export function RecipientAutocomplete({
  id,
  label,
  selected,
  options,
  excludeIds,
  onChange,
  disabled,
  autoFocus,
  invalid,
  action,
}: {
  id: string;
  label: string;
  selected: MailParty[];
  options: MailParty[];
  /** Ids already chosen in OTHER fields — never offered here (a contact sits in one field only). */
  excludeIds: Set<string>;
  onChange: (next: MailParty[]) => void;
  disabled?: boolean;
  autoFocus?: boolean;
  invalid?: boolean;
  action?: React.ReactNode;
}) {
  const [query, setQuery] = React.useState('');
  const [open, setOpen] = React.useState(false);
  const [highlight, setHighlight] = React.useState(0);
  const inputRef = React.useRef<HTMLInputElement>(null);

  const selectedIds = React.useMemo(() => new Set(selected.map((s) => s.userId)), [selected]);
  const hasQuery = query.trim() !== '';
  const matches = React.useMemo(() => {
    const q = query.trim().toLowerCase();
    if (q === '') return []; // empty field → no dropdown, no pre-populated list
    return options
      .filter((o) => o.userId && !excludeIds.has(o.userId) && !selectedIds.has(o.userId))
      // Match the mail ADDRESS only (not the display name); rows still show both.
      .filter((o) => o.address.toLowerCase().includes(q))
      .slice(0, MAX_SHOWN);
  }, [options, excludeIds, selectedIds, query]);

  React.useEffect(() => setHighlight(0), [query, open]);

  const add = (party: MailParty) => {
    onChange([...selected, party]);
    setQuery('');
    setOpen(true);
    inputRef.current?.focus();
  };
  const removeAt = (id: string) => onChange(selected.filter((s) => s.userId !== id));

  const onKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setOpen(true);
      setHighlight((h) => Math.min(h + 1, matches.length - 1));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setHighlight((h) => Math.max(h - 1, 0));
    } else if (e.key === 'Enter' && open && matches[highlight]) {
      e.preventDefault();
      add(matches[highlight]);
    } else if (e.key === 'Escape') {
      setOpen(false);
    } else if (e.key === 'Backspace' && query === '' && selected.length > 0) {
      removeAt(selected[selected.length - 1].userId!);
    }
  };

  const listId = `${id}-listbox`;

  return (
    <div className="space-y-1">
      <div className="flex items-center justify-between">
        <label htmlFor={id} className="text-xs font-medium text-muted-foreground">
          {label}
        </label>
        {action}
      </div>
      <div className="relative">
        <div
          className={cn(
            'flex min-h-11 flex-wrap items-center gap-1 rounded-xl border border-input bg-background px-2 py-1',
            'focus-within:ring-2 focus-within:ring-ring',
            invalid && 'border-destructive',
            disabled && 'cursor-not-allowed opacity-50',
          )}
          onClick={() => inputRef.current?.focus()}
        >
          {selected.map((p) => (
            <span
              key={p.userId}
              className="inline-flex items-center gap-1 rounded-full bg-primary/10 px-2 py-0.5 text-xs text-primary"
              title={p.address}
            >
              {p.name}
              <button
                type="button"
                aria-label={`Remove ${p.name}`}
                className="text-primary/70 hover:text-primary"
                onClick={(e) => {
                  e.stopPropagation();
                  removeAt(p.userId!);
                }}
              >
                <X className="size-3" />
              </button>
            </span>
          ))}
          <input
            ref={inputRef}
            id={id}
            role="combobox"
            aria-expanded={open}
            aria-controls={listId}
            aria-autocomplete="list"
            autoComplete="off"
            autoFocus={autoFocus}
            disabled={disabled}
            value={query}
            onChange={(e) => {
              setQuery(e.target.value);
              setOpen(true);
            }}
            onFocus={() => setOpen(true)}
            onBlur={() => setOpen(false)}
            onKeyDown={onKeyDown}
            placeholder={selected.length === 0 ? 'Type a name or address…' : ''}
            className="min-w-[8rem] flex-1 bg-transparent px-1 py-0.5 text-sm outline-none placeholder:text-muted-foreground"
          />
        </div>

        {open && hasQuery ? (
          <ul
            id={listId}
            role="listbox"
            className={cn(
              surface('card'),
              'absolute z-10 mt-1 max-h-56 w-full overflow-auto py-1 shadow-card-hover',
            )}
          >
            {matches.length === 0 ? (
              // No allowed contact matches — a free-typed address can never be added.
              <li className="px-3 py-1.5 text-sm text-muted-foreground">No matches</li>
            ) : null}
            {matches.map((o, i) => (
              <li key={o.userId} role="option" aria-selected={i === highlight}>
                <button
                  type="button"
                  // onMouseDown (not onClick) so selection happens before the input blur closes the list.
                  onMouseDown={(e) => {
                    e.preventDefault();
                    add(o);
                  }}
                  onMouseEnter={() => setHighlight(i)}
                  className={cn(
                    'flex w-full items-center justify-between gap-3 px-3 py-1.5 text-left text-sm',
                    i === highlight ? 'bg-accent' : 'hover:bg-accent/60',
                  )}
                >
                  <span className="truncate font-medium">{o.name}</span>
                  <span className="shrink-0 truncate text-xs text-muted-foreground">{o.address}</span>
                </button>
              </li>
            ))}
          </ul>
        ) : null}
      </div>
    </div>
  );
}
