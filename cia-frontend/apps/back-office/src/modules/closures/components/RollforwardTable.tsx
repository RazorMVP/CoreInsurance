// Shared roll-forward table for IFRS-style disclosure shapes
// (e.g. IFRS 17 §103, IFRS 9 §B5.5.39). Renders:
//   - Opening balance row (italic "(start)" hint, no sign indicator)
//   - One row per signed movement (+ or − in the leftmost gutter)
//   - Bold Closing balance row separated by a thicker top border
//
// All movement values come from a single `totals` record keyed by the
// schema field name. The page provides a sign-annotated array of
// `{ key, label, sign? }` rows in the order they should display. The
// closing row is identified by `key === 'closing'` OR `key === 'closingBalance'`.

interface RollforwardRow<T> {
  /** Field key on the `totals` record. */
  key:   keyof T;
  /** Human label rendered in the second column. */
  label: string;
  /** Optional sign indicator rendered in the leftmost gutter. */
  sign?: '+' | '−';
}

interface RollforwardTableProps<T> {
  rows:   RollforwardRow<T>[];
  totals: T;
  /** Defaults to `formatNGN`. Override for currency variants. */
  format?: (amount: number) => string;
}

function defaultFormat(amount: number): string {
  return `₦${amount.toLocaleString('en-GB', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

export function RollforwardTable<T extends Record<string, number>>({
  rows,
  totals,
  format = defaultFormat,
}: RollforwardTableProps<T>) {
  return (
    <table className="w-full text-sm border-collapse">
      <tbody>
        {rows.map((r) => {
          const isClosing = r.key === 'closing' || r.key === 'closingBalance';
          const isOpening = r.key === 'opening' || r.key === 'openingBalance';
          const amount    = totals[r.key];
          return (
            <tr
              key={String(r.key)}
              className={`border-b last:border-0 ${isClosing ? 'border-t-2 border-foreground/20 font-semibold' : ''}`}
            >
              <td className="py-1.5 px-2 w-12 text-center font-mono text-xs text-muted-foreground">
                {r.sign ?? ''}
              </td>
              <td className="py-1.5 px-2">
                {r.label}
                {(isOpening || isClosing) && (
                  <span className="ml-2 text-xs text-muted-foreground">
                    {isOpening ? '(start)' : '(end)'}
                  </span>
                )}
              </td>
              <td className="py-1.5 px-2 text-right font-mono">{format(amount)}</td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}
