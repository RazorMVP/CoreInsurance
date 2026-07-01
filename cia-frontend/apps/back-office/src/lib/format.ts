/**
 * Full-precision Naira formatter for list/table cells and detail rows —
 * `₦1,234,567`.
 *
 * A `null`/`undefined` value renders an em-dash (`—`) so a money field that is
 * missing from the payload, not-yet-loaded, or legitimately absent degrades
 * gracefully instead of throwing `Cannot read properties of undefined (reading
 * 'toLocaleString')` — the white-screen that hit the policy + quote list pages
 * when their summary endpoints omitted a field the cell expected.
 *
 * Use this for every money cell that reads a value off row data. For the
 * abbreviated dashboard StatCard form (`₦1.2M`) see StatCardRow's local helper.
 */
export function formatNaira(value: number | null | undefined): string {
  return value == null ? '—' : `₦${value.toLocaleString()}`;
}

/** The em-dash rendered for any absent/null value. */
const EMPTY = '—';

/**
 * Date-only formatter for list/detail cells — `30 Jun 2026` (en-GB
 * `dd Mon yyyy`). `null`/`undefined` renders `—`. Consolidates the identical
 * per-page `formatDate` helpers and makes them null-tolerant so a cell
 * repointed to a nullable date can't white-screen on `new Date(undefined)`.
 */
export function formatDate(iso: string | null | undefined): string {
  return iso == null
    ? EMPTY
    : new Date(iso).toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric' });
}

/**
 * Compact ISO-timestamp formatter used by the audit tables — strips the `T`
 * and trailing `Z` (`2026-06-30T19:04:28Z` → `2026-06-30 19:04:28`).
 * `null`/`undefined` renders `—`.
 */
export function formatTimestamp(iso: string | null | undefined): string {
  return iso == null ? EMPTY : iso.replace('T', ' ').replace('Z', '');
}

/**
 * Renders a SCREAMING_SNAKE enum/status value as a human label
 * (`BANK_TRANSFER` → `bank transfer`). `null`/`undefined` renders `—`.
 */
export function formatEnumLabel(value: string | null | undefined): string {
  return value == null ? EMPTY : value.replace(/_/g, ' ').toLowerCase();
}
