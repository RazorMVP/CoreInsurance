import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts';
import type { UsageHistoryEntryDto } from '@cia/api-client';

export function UsageChart({ history }: { history: UsageHistoryEntryDto[] }) {
  const data = [...history].reverse(); // oldest→newest for the x-axis
  return (
    <div className="h-72 w-full rounded-lg border border-border bg-card p-4">
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={data} margin={{ top: 8, right: 8, bottom: 8, left: 0 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" />
          <XAxis dataKey="date" tick={{ fontSize: 10, fill: 'var(--muted-foreground)' }} />
          <YAxis tick={{ fontSize: 10, fill: 'var(--muted-foreground)' }} />
          <Tooltip contentStyle={{ background: 'var(--card)', border: '1px solid var(--border)', color: 'var(--foreground)' }} />
          <Bar dataKey="success" stackId="a" fill="var(--primary)" />
          <Bar dataKey="clientError" stackId="a" fill="oklch(0.7 0.15 60)" />
          <Bar dataKey="serverError" stackId="a" fill="oklch(0.6 0.2 25)" />
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}
