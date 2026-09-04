const DASH = '—';

export function formatNaira(v: number | null | undefined): string {
  if (v === null || v === undefined || Number.isNaN(v)) return DASH;
  return `₦${v.toLocaleString('en-NG', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

export function formatInt(v: number | null | undefined): string {
  if (v === null || v === undefined || Number.isNaN(v)) return DASH;
  return v.toLocaleString('en-NG');
}

export function formatPercent(v: number | null | undefined): string {
  if (v === null || v === undefined || Number.isNaN(v)) return DASH;
  return `${(v * 100).toFixed(1)}%`;
}

export function formatDate(v: string | null | undefined): string {
  if (!v) return DASH;
  const d = new Date(v);
  return Number.isNaN(d.getTime()) ? DASH : d.toLocaleDateString('en-NG', { year: 'numeric', month: 'short', day: '2-digit' });
}

export function formatTimestamp(v: string | null | undefined): string {
  if (!v) return DASH;
  const d = new Date(v);
  return Number.isNaN(d.getTime()) ? DASH : d.toLocaleString('en-NG', { dateStyle: 'medium', timeStyle: 'short' });
}
