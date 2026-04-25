import { useDashboardStats, useApprovalQueue, useLossRatioTrend, useRenewalsDue } from './hooks/useDashboard';
import StatCardRow from './components/StatCardRow';
import ApprovalQueueWidget from './components/ApprovalQueueWidget';
import LossRatioSparkline from './components/LossRatioSparkline';
import RenewalsDueStrip from './components/RenewalsDueStrip';

export default function DashboardPage() {
  const { data: stats,    isLoading: statsLoading }    = useDashboardStats();
  const { data: queue,    isLoading: queueLoading }    = useApprovalQueue();
  const { data: lossData, isLoading: lossLoading }     = useLossRatioTrend();
  const { data: renewals, isLoading: renewalsLoading } = useRenewalsDue();

  return (
    <div className="p-6 space-y-6">
      {/* 1 — 8 KPI stat cards */}
      <StatCardRow stats={stats} isLoading={statsLoading} />

      {/* 2 — Approval queue + Loss ratio sparkline (side by side on wide screens) */}
      <div className="grid grid-cols-1 gap-6 xl:grid-cols-2">
        <ApprovalQueueWidget queue={queue} isLoading={queueLoading} />
        <LossRatioSparkline  data={lossData} isLoading={lossLoading} />
      </div>

      {/* 3 — Renewals due 7-day strip */}
      <RenewalsDueStrip days={renewals} isLoading={renewalsLoading} />
    </div>
  );
}
