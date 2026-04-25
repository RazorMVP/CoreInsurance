import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip,
  ReferenceLine, ResponsiveContainer, Cell,
} from 'recharts';
import { Skeleton } from '@cia/ui';
import { HugeiconsIcon } from '@hugeicons/react';
import { BarChartIcon } from '@hugeicons/core-free-icons';
import type { LossRatioMonth } from '../hooks/useDashboard';

interface Props {
  data: LossRatioMonth[] | undefined;
  isLoading: boolean;
}

function barColor(pct: number): string {
  if (pct >= 100) return 'oklch(0.55 0.22 25)';   // red — loss-making
  if (pct >= 75)  return 'oklch(0.72 0.18 80)';   // amber — watch zone
  return 'oklch(0.65 0.13 197)';                   // teal — healthy
}

const CustomTooltip = ({ active, payload, label }: any) => {
  if (!active || !payload?.length) return null;
  const pct = Number(payload[0]?.value ?? 0);
  return (
    <div className="rounded-lg border bg-card px-3 py-2 shadow-md text-xs space-y-0.5">
      <p className="font-semibold text-foreground">{label}</p>
      <p className="text-muted-foreground">Loss Ratio: <span className="font-medium text-foreground">{pct.toFixed(1)}%</span></p>
    </div>
  );
};

export default function LossRatioSparkline({ data, isLoading }: Props) {
  return (
    <div className="rounded-lg bg-card" style={{ boxShadow: '0 0 0 1px var(--border)' }}>
      <div
        className="flex items-center justify-between px-5 py-4"
        style={{ boxShadow: '0 1px 0 var(--border)' }}
      >
        <div className="flex items-center gap-2">
          <HugeiconsIcon icon={BarChartIcon} size={16} color="var(--primary)" strokeWidth={1.75} />
          <h2 className="text-sm font-semibold text-foreground">Loss Ratio — Last 6 Months</h2>
        </div>
        <div className="flex items-center gap-3 text-[10px]">
          <span className="flex items-center gap-1"><span className="inline-block h-2 w-2 rounded-sm bg-[oklch(0.65_0.13_197)]" />{'<75%'}</span>
          <span className="flex items-center gap-1"><span className="inline-block h-2 w-2 rounded-sm bg-[oklch(0.72_0.18_80)]" />75–99%</span>
          <span className="flex items-center gap-1"><span className="inline-block h-2 w-2 rounded-sm bg-[oklch(0.55_0.22_25)]" />≥100%</span>
        </div>
      </div>

      <div className="px-5 py-4" style={{ height: 200 }}>
        {isLoading || !data ? (
          <div className="flex items-end gap-3 h-full">
            {[60, 80, 45, 90, 55, 70].map((h, i) => (
              <Skeleton key={i} className="flex-1 rounded-sm" style={{ height: `${h}%` }} />
            ))}
          </div>
        ) : (
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={data} margin={{ top: 4, right: 8, left: -24, bottom: 0 }} barSize={28}>
              <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" vertical={false} />
              <XAxis dataKey="month" tick={{ fontSize: 11 }} axisLine={false} tickLine={false} />
              <YAxis tick={{ fontSize: 11 }} axisLine={false} tickLine={false} tickFormatter={v => `${v}%`} domain={[0, 'dataMax + 20']} />
              <Tooltip content={<CustomTooltip />} cursor={{ fill: 'var(--secondary)' }} />
              <ReferenceLine y={100} stroke="oklch(0.55 0.22 25)" strokeDasharray="4 3" strokeWidth={1.5} label={{ value: '100%', position: 'right', fontSize: 10, fill: 'oklch(0.55 0.22 25)' }} />
              <ReferenceLine y={75}  stroke="oklch(0.72 0.18 80)" strokeDasharray="4 3" strokeWidth={1} />
              <Bar dataKey="lossRatioPct" radius={[3, 3, 0, 0]}>
                {data.map((entry, i) => (
                  <Cell key={i} fill={barColor(Number(entry.lossRatioPct))} />
                ))}
              </Bar>
            </BarChart>
          </ResponsiveContainer>
        )}
      </div>
    </div>
  );
}
