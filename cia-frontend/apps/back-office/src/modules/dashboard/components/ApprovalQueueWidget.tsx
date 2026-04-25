import { Link } from 'react-router-dom';
import { Badge, Skeleton } from '@cia/ui';
import { HugeiconsIcon } from '@hugeicons/react';
import { CheckmarkCircle01Icon, ArrowRight01Icon } from '@hugeicons/core-free-icons';
import type { ApprovalQueue } from '../hooks/useDashboard';

interface QueueItem {
  label: string;
  count: (q: ApprovalQueue) => number;
  path: string;
  badge: 'pending' | 'active';
}

const ITEMS: QueueItem[] = [
  { label: 'Policies',     count: q => q.policies,     path: '/policies',     badge: 'pending' },
  { label: 'Quotes',       count: q => q.quotes,        path: '/quotation',    badge: 'pending' },
  { label: 'Endorsements', count: q => q.endorsements,  path: '/endorsements', badge: 'pending' },
  { label: 'Claims',       count: q => q.claims,        path: '/claims',       badge: 'pending' },
  { label: 'Receipts',     count: q => q.receipts,      path: '/finance',      badge: 'pending' },
  { label: 'Payments',     count: q => q.payments,      path: '/finance',      badge: 'pending' },
];

interface Props {
  queue: ApprovalQueue | undefined;
  isLoading: boolean;
}

export default function ApprovalQueueWidget({ queue, isLoading }: Props) {
  const total = queue
    ? queue.policies + queue.quotes + queue.endorsements + queue.claims + queue.receipts + queue.payments
    : 0;

  return (
    <div className="rounded-lg bg-card" style={{ boxShadow: '0 0 0 1px var(--border)' }}>
      {/* Header */}
      <div
        className="flex items-center justify-between px-5 py-4"
        style={{ boxShadow: '0 1px 0 var(--border)' }}
      >
        <div className="flex items-center gap-2">
          <HugeiconsIcon icon={CheckmarkCircle01Icon} size={16} color="var(--primary)" strokeWidth={1.75} />
          <h2 className="text-sm font-semibold text-foreground">Pending Approvals</h2>
          {!isLoading && total > 0 && (
            <Badge variant="pending" className="text-[10px] px-1.5 py-0.5">{total}</Badge>
          )}
        </div>
        <span className="text-xs text-muted-foreground">Items awaiting your action</span>
      </div>

      {/* Queue rows */}
      {isLoading ? (
        <div className="divide-y">
          {ITEMS.map((item) => (
            <div key={item.label} className="flex items-center justify-between px-5 py-3.5">
              <Skeleton className="h-4 w-24" />
              <Skeleton className="h-5 w-8 rounded-full" />
            </div>
          ))}
        </div>
      ) : (
        <ul className="divide-y divide-border">
          {ITEMS.map((item) => {
            const count = queue ? item.count(queue) : 0;
            return (
              <li key={item.label}>
                <Link
                  to={item.path}
                  className="flex items-center justify-between px-5 py-3.5 hover:bg-secondary/40 transition-colors group"
                >
                  <span className="text-sm text-foreground">{item.label}</span>
                  <div className="flex items-center gap-2">
                    {count > 0 ? (
                      <Badge variant="pending" className="text-[10px] px-1.5 py-0.5 tabular-nums">
                        {count}
                      </Badge>
                    ) : (
                      <span className="text-xs text-muted-foreground">—</span>
                    )}
                    <HugeiconsIcon
                      icon={ArrowRight01Icon}
                      size={13}
                      color="currentColor"
                      strokeWidth={1.75}
                      className="text-muted-foreground opacity-0 group-hover:opacity-100 transition-opacity"
                    />
                  </div>
                </Link>
              </li>
            );
          })}
        </ul>
      )}

      {/* Footer */}
      {!isLoading && total === 0 && (
        <p className="px-5 py-4 text-xs text-muted-foreground text-center">
          No pending approvals — all clear.
        </p>
      )}
    </div>
  );
}
