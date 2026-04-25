import {
  BarChart, Bar, LineChart, Line, PieChart, Pie, Cell,
  XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer,
} from 'recharts';
import type { ReportChart as ReportChartConfig } from '../../types/report.types';

const COLORS = [
  'oklch(0.65 0.13 197)',
  'oklch(0.55 0.18 245)',
  'oklch(0.72 0.18 150)',
  'oklch(0.68 0.20 35)',
  'oklch(0.60 0.22 300)',
  'oklch(0.75 0.17 80)',
];

interface Props {
  chart: ReportChartConfig;
  data: Record<string, unknown>[];
}

export default function ReportChart({ chart, data }: Props) {
  if (!chart || chart.type === 'TABLE_ONLY' || data.length === 0) return null;

  const xKey = chart.xAxis ?? '';
  const yKey = chart.yAxis ?? '';

  return (
    <div style={{ height: 280 }} className="mt-4">
      <ResponsiveContainer width="100%" height="100%">
        {chart.type === 'BAR' ? (
          <BarChart data={data} margin={{ top: 4, right: 16, left: 0, bottom: 4 }}>
            <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" />
            <XAxis dataKey={xKey} tick={{ fontSize: 11 }} />
            <YAxis tick={{ fontSize: 11 }} />
            <Tooltip />
            <Bar dataKey={yKey} fill={COLORS[0]} radius={[3, 3, 0, 0]} />
          </BarChart>
        ) : chart.type === 'LINE' ? (
          <LineChart data={data} margin={{ top: 4, right: 16, left: 0, bottom: 4 }}>
            <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" />
            <XAxis dataKey={xKey} tick={{ fontSize: 11 }} />
            <YAxis tick={{ fontSize: 11 }} />
            <Tooltip />
            <Legend />
            <Line type="monotone" dataKey={yKey} stroke={COLORS[0]} strokeWidth={2} dot={false} />
          </LineChart>
        ) : (
          <PieChart>
            <Pie data={data} dataKey={yKey} nameKey={xKey} cx="50%" cy="50%" outerRadius={100} label>
              {data.map((_, i) => (
                <Cell key={i} fill={COLORS[i % COLORS.length]} />
              ))}
            </Pie>
            <Tooltip />
            <Legend />
          </PieChart>
        )}
      </ResponsiveContainer>
    </div>
  );
}
