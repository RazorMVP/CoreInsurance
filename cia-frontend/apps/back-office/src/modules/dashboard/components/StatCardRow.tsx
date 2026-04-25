import { Skeleton } from '@cia/ui';
import { HugeiconsIcon } from '@hugeicons/react';
import {
  Shield01Icon,
  AlertCircleIcon,
  CheckmarkCircle01Icon,
  Money01Icon,
  FileEditIcon,
  Calendar01Icon,
  Invoice01Icon,
  RepeatIcon,
} from '@hugeicons/core-free-icons';
import type { DashboardStats } from '../hooks/useDashboard';

function formatNaira(val: number): string {
  if (val >= 1_000_000_000) return `₦${(val / 1_000_000_000).toFixed(1)}B`;
  if (val >= 1_000_000)     return `₦${(val / 1_000_000).toFixed(1)}M`;
  if (val >= 1_000)         return `₦${(val / 1_000).toFixed(0)}K`;
  return `₦${val.toFixed(0)}`;
}

interface Card {
  label: string;
  value: (s: DashboardStats) => string;
  sub: string;
  icon: React.ComponentProps<typeof HugeiconsIcon>['icon'];
  accent: string;
}

const CARDS: Card[] = [
  { label: 'Active Policies',       value: s => s.activePolicies.toLocaleString(),                    sub: 'In force',                      icon: Shield01Icon,        accent: 'text-blue-600 bg-blue-50' },
  { label: 'Open Claims',           value: s => s.openClaims.toLocaleString(),                         sub: 'Awaiting settlement',           icon: AlertCircleIcon,     accent: 'text-red-600 bg-red-50' },
  { label: 'Pending Approvals',     value: s => s.pendingApprovals.toLocaleString(),                   sub: 'Across all modules',            icon: CheckmarkCircle01Icon, accent: 'text-amber-600 bg-amber-50' },
  { label: 'Premiums (MTD)',        value: s => formatNaira(s.premiumsMtd),                            sub: 'Month-to-date receipts',        icon: Money01Icon,         accent: 'text-emerald-600 bg-emerald-50' },
  { label: 'Claims Reserve',        value: s => formatNaira(s.claimsReserveTotal),                     sub: 'Total outstanding reserves',    icon: FileEditIcon,        accent: 'text-orange-600 bg-orange-50' },
  { label: 'Renewals Due (30d)',    value: s => s.renewalsDue30Days.toLocaleString(),                  sub: 'Policies expiring soon',        icon: Calendar01Icon,      accent: 'text-violet-600 bg-violet-50' },
  { label: 'Outstanding Premium',   value: s => formatNaira(s.outstandingPremium),                     sub: 'Unpaid debit notes',            icon: Invoice01Icon,       accent: 'text-pink-600 bg-pink-50' },
  { label: 'RI Utilisation',        value: s => `${Number(s.riUtilisationPct).toFixed(1)}%`,          sub: 'Treaty capacity used',          icon: RepeatIcon,          accent: 'text-teal-600 bg-teal-50' },
];

interface Props {
  stats: DashboardStats | undefined;
  isLoading: boolean;
}

export default function StatCardRow({ stats, isLoading }: Props) {
  return (
    <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
      {CARDS.map((card) => (
        <div
          key={card.label}
          className="rounded-lg bg-card p-5 space-y-3"
          style={{ boxShadow: '0 0 0 1px var(--border)' }}
        >
          <div className="flex items-center justify-between">
            <p className="text-xs font-medium text-muted-foreground">{card.label}</p>
            <span className={`flex h-8 w-8 items-center justify-center rounded-lg ${card.accent}`}>
              <HugeiconsIcon icon={card.icon} size={15} color="currentColor" strokeWidth={1.75} />
            </span>
          </div>
          {isLoading || !stats ? (
            <>
              <Skeleton className="h-7 w-24" />
              <Skeleton className="h-3 w-28" />
            </>
          ) : (
            <>
              <p className="font-display text-2xl font-semibold tracking-tight text-foreground">
                {card.value(stats)}
              </p>
              <p className="text-xs text-muted-foreground">{card.sub}</p>
            </>
          )}
        </div>
      ))}
    </div>
  );
}
