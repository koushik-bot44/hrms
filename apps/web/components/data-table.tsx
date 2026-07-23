'use client';

import * as React from 'react';
import {
  type ColumnDef,
  type SortingState,
  flexRender,
  getCoreRowModel,
  getFilteredRowModel,
  getSortedRowModel,
  useReactTable,
} from '@tanstack/react-table';
import { ArrowDown, ArrowUp, ArrowUpDown, Search } from 'lucide-react';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { Input } from '@/components/ui/input';

interface DataTableProps<TData, TValue> {
  columns: ColumnDef<TData, TValue>[];
  data: TData[];
  searchPlaceholder?: string;
  /** Set false to hide the built-in client-side search box (e.g. when the caller owns a server search). */
  searchable?: boolean;
  /** Wrap the table in the floating Card look (rounded-2xl + shadow), matching the accountant pilot. */
  carded?: boolean;
  /** Classes for the table container — defaults to a rounded, bordered box; pass '' to card it externally. */
  containerClassName?: string;
  /** Shown when there are no rows (e.g. an <EmptyState/>). */
  emptyState?: React.ReactNode;
  /** Extra toolbar controls (filters) rendered next to the search box. */
  toolbar?: React.ReactNode;
}

/** Reusable list table: client-side search + sortable headers. The base for list views. */
export function DataTable<TData, TValue>({
  columns,
  data,
  searchPlaceholder = 'Search…',
  searchable = true,
  carded = false,
  containerClassName,
  emptyState,
  toolbar,
}: DataTableProps<TData, TValue>) {
  // The carded look matches the pilot's static Card; the default stays a plain rounded border.
  const container =
    containerClassName ??
    (carded ? 'overflow-hidden rounded-2xl border bg-card shadow-card' : 'rounded-lg border');
  const [sorting, setSorting] = React.useState<SortingState>([]);
  const [globalFilter, setGlobalFilter] = React.useState('');

  const table = useReactTable({
    data,
    columns,
    state: { sorting, globalFilter },
    onSortingChange: setSorting,
    onGlobalFilterChange: setGlobalFilter,
    getCoreRowModel: getCoreRowModel(),
    getSortedRowModel: getSortedRowModel(),
    getFilteredRowModel: getFilteredRowModel(),
  });

  return (
    <div className="space-y-3">
      {searchable || toolbar ? (
        <div className="flex flex-wrap items-center gap-3">
          {searchable ? (
            <div className="relative w-full max-w-sm">
              <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
              <Input
                value={globalFilter}
                onChange={(e) => setGlobalFilter(e.target.value)}
                placeholder={searchPlaceholder}
                className="pl-9"
                aria-label="Search"
              />
            </div>
          ) : null}
          {toolbar}
        </div>
      ) : null}

      <div className={container}>
        <Table>
          <TableHeader>
            {table.getHeaderGroups().map((hg) => (
              <TableRow key={hg.id} className="hover:bg-transparent">
                {hg.headers.map((header) => {
                  const canSort = header.column.getCanSort();
                  const sorted = header.column.getIsSorted();
                  return (
                    <TableHead key={header.id}>
                      {header.isPlaceholder ? null : canSort ? (
                        <button
                          type="button"
                          onClick={header.column.getToggleSortingHandler()}
                          className="inline-flex items-center gap-1 transition-colors hover:text-foreground"
                        >
                          {flexRender(header.column.columnDef.header, header.getContext())}
                          {sorted === 'asc' ? (
                            <ArrowUp className="size-3.5" />
                          ) : sorted === 'desc' ? (
                            <ArrowDown className="size-3.5" />
                          ) : (
                            <ArrowUpDown className="size-3.5 opacity-50" />
                          )}
                        </button>
                      ) : (
                        flexRender(header.column.columnDef.header, header.getContext())
                      )}
                    </TableHead>
                  );
                })}
              </TableRow>
            ))}
          </TableHeader>
          <TableBody>
            {table.getRowModel().rows.length ? (
              table.getRowModel().rows.map((row) => (
                <TableRow key={row.id}>
                  {row.getVisibleCells().map((cell) => (
                    <TableCell key={cell.id}>
                      {flexRender(cell.column.columnDef.cell, cell.getContext())}
                    </TableCell>
                  ))}
                </TableRow>
              ))
            ) : (
              <TableRow className="hover:bg-transparent">
                <TableCell colSpan={columns.length} className="p-0">
                  {emptyState ?? (
                    <div className="py-12 text-center text-sm text-muted-foreground">
                      No results.
                    </div>
                  )}
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </div>
    </div>
  );
}
