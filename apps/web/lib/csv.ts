/**
 * Minimal, dependency-free CSV export (client-side). Fields containing a comma, quote or newline are
 * quoted with quotes doubled; rows are CRLF-terminated and the file is UTF-8 with a BOM so Excel opens
 * it cleanly. Used by the read-only viewer attendance exports — no server round-trip.
 */

export type CsvCell = string | number | boolean | null | undefined;

function escapeCell(value: CsvCell): string {
  const s = value == null ? '' : String(value);
  return /[",\n\r]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}

/** Build a CSV string from a header row + data rows. */
export function toCsv(headers: string[], rows: CsvCell[][]): string {
  return [headers, ...rows].map((r) => r.map(escapeCell).join(',')).join('\r\n');
}

/** Trigger a client-side download of {@code csv} as {@code filename} (Blob + object URL). */
export function downloadCsv(filename: string, csv: string): void {
  if (typeof document === 'undefined') return;
  const bom = '﻿'; // so Excel detects UTF-8
  const blob = new Blob([bom + csv], { type: 'text/csv;charset=utf-8;' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}
