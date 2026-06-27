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
