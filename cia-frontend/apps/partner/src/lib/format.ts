// `formatNaira` / `formatDate` are unused by any partner page today — kept deliberately as a
// mirrored set matching back-office's `lib/format.ts` API so pages can move between apps without
// import churn. They are fully unit-tested; that coverage exercises the helpers themselves, not
// any live page path.
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
