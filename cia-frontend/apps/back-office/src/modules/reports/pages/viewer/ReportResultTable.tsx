import type { ReportField } from '../../types/report.types';

interface Props {
  columns: ReportField[];
  rows: Record<string, unknown>[];
  totalRows: number;
}

function formatCell(value: unknown, type: string): string {
  if (value === null || value === undefined) return '—';
  if (type === 'MONEY') {
    const num = Number(value);
    if (isNaN(num)) return String(value);
    return `₦${num.toLocaleString('en-NG', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
  }
  if (type === 'PERCENT') {
    return `${Number(value).toFixed(2)}%`;
  }
  if (type === 'DATE') {
    try {
      return new Date(String(value)).toLocaleDateString('en-GB', {
        day: '2-digit', month: 'short', year: 'numeric',
      });
    } catch {
      return String(value);
    }
  }
  return String(value);
}

export default function ReportResultTable({ columns, rows, totalRows }: Props) {
  if (rows.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center py-16 text-muted-foreground">
        <p className="text-sm">No results for the selected filters.</p>
      </div>
    );
  }

  return (
    <div className="space-y-2">
      <p className="text-xs text-muted-foreground text-right">
        {totalRows.toLocaleString()} record{totalRows !== 1 ? 's' : ''}
      </p>
      <div className="rounded-md border overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b bg-secondary/40">
              {columns.map((col) => (
                <th key={col.key} className="h-10 px-3 text-left text-xs font-medium text-muted-foreground whitespace-nowrap">
                  {col.label}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((row, i) => (
              <tr key={i} className="border-b last:border-0 hover:bg-secondary/30 transition-colors">
                {columns.map((col) => (
                  <td key={col.key} className="px-3 py-2.5 whitespace-nowrap text-sm">
                    {formatCell(row[col.key], col.type)}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
