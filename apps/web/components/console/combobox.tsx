'use client';

import * as React from 'react';
import { Command as CommandPrimitive } from 'cmdk';
import { Check, ChevronsUpDown, Plus, Search } from 'lucide-react';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { cn } from '@/lib/utils';

/**
 * The console's ONE searchable dropdown.
 *
 * The UX standard: every filter and picker is a searchable dropdown — company, team, building, status,
 * person, payroll cycle — and no filter is a scroll-to-find list. There are 212 people and a growing
 * list of companies; a bare `<select>` stops being usable well before that, and the repo had seven
 * copies of a hand-rolled `SELECT_CLASS` string with no search at all.
 *
 * One component, reused everywhere. Divergent one-off pickers are a defect by the standard, so this
 * takes the awkward cases as props — an optional "create new" action for the inline new-building flow,
 * an explicit clearable empty option for filters — rather than being forked per screen.
 */

export interface ComboboxOption {
  value: string;
  label: string;
  /** Second line — company under a person, role under a terminal. */
  hint?: string;
  /** Right-aligned, for group counts. */
  badge?: string;
}

export interface ComboboxProps {
  options: ComboboxOption[];
  value: string | null;
  onChange: (value: string) => void;
  placeholder?: string;
  searchPlaceholder?: string;
  /** Label for the "any / all" choice. Omit to make the field mandatory. */
  emptyOptionLabel?: string;
  /** Renders a create action when the search finds nothing — the inline "new building" flow. */
  onCreate?: (name: string) => void;
  createLabel?: (name: string) => string;
  disabled?: boolean;
  className?: string;
  /** Accessible name; required because these usually sit in a toolbar with no visible label. */
  ariaLabel: string;
}

export function Combobox({
  options,
  value,
  onChange,
  placeholder = 'Select…',
  searchPlaceholder = 'Search…',
  emptyOptionLabel,
  onCreate,
  createLabel = (n) => `Create “${n}”`,
  disabled,
  className,
  ariaLabel,
}: ComboboxProps) {
  const [open, setOpen] = React.useState(false);
  const [query, setQuery] = React.useState('');

  const selected = options.find((o) => o.value === value) ?? null;
  const trimmed = query.trim();
  // cmdk filters for us; this only decides whether the create affordance is worth offering.
  const exactExists = options.some((o) => o.label.toLowerCase() === trimmed.toLowerCase());

  const choose = (v: string) => {
    onChange(v);
    setOpen(false);
    setQuery('');
  };

  return (
    <Popover
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        if (!next) setQuery('');
      }}
    >
      <PopoverTrigger asChild>
        <button
          type="button"
          role="combobox"
          aria-expanded={open}
          aria-label={ariaLabel}
          disabled={disabled}
          className={cn(
            'inline-flex h-11 min-w-0 items-center justify-between gap-2 rounded-md border border-input',
            'bg-background px-3 text-sm ring-offset-background transition-colors',
            'hover:bg-accent hover:text-accent-foreground',
            'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2',
            'disabled:cursor-not-allowed disabled:opacity-50',
            className,
          )}
        >
          <span className={cn('truncate', !selected && 'text-muted-foreground')}>
            {selected ? selected.label : (emptyOptionLabel ?? placeholder)}
          </span>
          <ChevronsUpDown className="size-4 shrink-0 opacity-50" aria-hidden />
        </button>
      </PopoverTrigger>

      <PopoverContent className="w-[min(22rem,calc(100vw-2rem))] p-0">
        <CommandPrimitive
          // Match on the hint too, so "Screatives" finds the people in it.
          filter={(itemValue, search, keywords) => {
            const haystack = `${itemValue} ${(keywords ?? []).join(' ')}`.toLowerCase();
            return haystack.includes(search.toLowerCase()) ? 1 : 0;
          }}
          className="overflow-hidden rounded-xl"
        >
          <div className="flex items-center gap-2 border-b border-border px-3">
            <Search className="size-4 shrink-0 text-muted-foreground" aria-hidden />
            <CommandPrimitive.Input
              value={query}
              onValueChange={setQuery}
              placeholder={searchPlaceholder}
              className="h-11 w-full bg-transparent text-sm outline-none placeholder:text-muted-foreground"
            />
          </div>

          <CommandPrimitive.List className="max-h-72 overflow-y-auto p-1">
            <CommandPrimitive.Empty className="px-3 py-6 text-center text-sm text-muted-foreground">
              {onCreate && trimmed ? null : 'Nothing matches that.'}
            </CommandPrimitive.Empty>

            {emptyOptionLabel ? (
              <CommandPrimitive.Item
                value={emptyOptionLabel}
                onSelect={() => choose('')}
                className={ITEM_CLASS}
              >
                <Check className={cn('size-4', value ? 'opacity-0' : 'opacity-100')} aria-hidden />
                <span className="truncate text-muted-foreground">{emptyOptionLabel}</span>
              </CommandPrimitive.Item>
            ) : null}

            {options.map((o) => (
              <CommandPrimitive.Item
                key={o.value}
                value={o.label}
                keywords={o.hint ? [o.hint] : undefined}
                onSelect={() => choose(o.value)}
                className={ITEM_CLASS}
              >
                <Check
                  className={cn('size-4 shrink-0', value === o.value ? 'opacity-100' : 'opacity-0')}
                  aria-hidden
                />
                <span className="min-w-0 flex-1">
                  <span className="block truncate">{o.label}</span>
                  {o.hint ? (
                    <span className="block truncate text-xs text-muted-foreground">{o.hint}</span>
                  ) : null}
                </span>
                {o.badge ? (
                  <span className="shrink-0 tabular-nums text-xs text-muted-foreground">
                    {o.badge}
                  </span>
                ) : null}
              </CommandPrimitive.Item>
            ))}

            {onCreate && trimmed && !exactExists ? (
              <CommandPrimitive.Item
                value={`__create__${trimmed}`}
                keywords={[trimmed]}
                onSelect={() => {
                  onCreate(trimmed);
                  setOpen(false);
                  setQuery('');
                }}
                className={cn(ITEM_CLASS, 'text-primary')}
              >
                <Plus className="size-4 shrink-0" aria-hidden />
                <span className="truncate">{createLabel(trimmed)}</span>
              </CommandPrimitive.Item>
            ) : null}
          </CommandPrimitive.List>
        </CommandPrimitive>
      </PopoverContent>
    </Popover>
  );
}

const ITEM_CLASS =
  'flex cursor-pointer items-center gap-2 rounded-lg px-2.5 py-2 text-sm outline-none ' +
  'data-[selected=true]:bg-accent data-[selected=true]:text-accent-foreground';
