import { useUsage } from '@cia/api-client';
import { useSelectedApp } from '../../app/AppContext';
import { formatInt, formatPercent } from '../../lib/format';
import { UsageChart } from './UsageChart';

function Stat({ label, value, tone }: { label: string; value: string; tone?: string }) {
  return (
    <div className="rounded-lg border border-border bg-card p-4">
      <p className="text-xs uppercase tracking-wide text-muted-foreground">{label}</p>
      <p className={`mt-1 text-2xl font-semibold ${tone ?? 'text-foreground'}`}>{value}</p>
    </div>
  );
}

export default function UsagePage() {
  const { selectedAppId } = useSelectedApp();
  const usageQuery = useUsage(selectedAppId ?? '');
  const u = usageQuery.data;

  return (
    <div className="space-y-6">
      <h1 className="text-xl font-semibold text-foreground">Usage</h1>
      {usageQuery.isLoading && <p className="text-sm text-muted-foreground">Loading…</p>}
      {u && (
        <>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
            <Stat label="Requests today" value={formatInt(u.today.total)} />
            <Stat label="Success today" value={formatInt(u.today.success)} tone="text-emerald-400" />
            <Stat label="Error rate today" value={formatPercent(u.errorRate)} tone={u.errorRate > 0.1 ? 'text-red-400' : 'text-foreground'} />
          </div>
          <UsageChart history={u.history} />
          <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
            <Stat label="Webhook deliveries" value={formatInt(u.webhookDeliveries.totalDeliveries)} />
            <Stat label="Delivered" value={formatInt(u.webhookDeliveries.successfulDeliveries)} tone="text-emerald-400" />
            <Stat label="Failed" value={formatInt(u.webhookDeliveries.failedDeliveries)} tone="text-red-400" />
            <Stat label="Active hooks" value={formatInt(u.webhookDeliveries.activeRegistrations)} />
          </div>
        </>
      )}
    </div>
  );
}
