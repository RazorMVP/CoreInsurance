import { Badge, Skeleton } from '@cia/ui';
import { HugeiconsIcon } from '@hugeicons/react';
import { ActivityIcon } from '@hugeicons/core-free-icons';
import type { RecentActivity } from '../hooks/useDashboard';

const ACTION_LABELS: Record<string, string> = {
  CREATE:   'created',
  UPDATE:   'updated',
  DELETE:   'deleted',
  APPROVE:  'approved',
  REJECT:   'rejected',
  SUBMIT:   'submitted',
  SEND:     'sent',
  CANCEL:   'cancelled',
  REVERSE:  'reversed',
  EXECUTE:  'executed',
  EXPORT:   'exported',
};

function actionLabel(action: string) {
  return ACTION_LABELS[action] ?? action.toLowerCase();
}

interface Props {
  items: RecentActivity[] | undefined;
  isLoading: boolean;
}

export default function RecentActivityFeed({ items, isLoading }: Props) {
  return (
    <div className="rounded-lg bg-card" style={{ boxShadow: '0 0 0 1px var(--border)' }}>
      <div
        className="flex items-center justify-between px-5 py-4"
        style={{ boxShadow: '0 1px 0 var(--border)' }}
      >
        <div className="flex items-center gap-2">
          <HugeiconsIcon icon={ActivityIcon} size={16} color="var(--primary)" strokeWidth={1.75} />
          <h2 className="text-sm font-semibold text-foreground">Recent Activity</h2>
        </div>
        <span className="text-xs text-muted-foreground">Last 10 actions</span>
      </div>

      {isLoading ? (
        <ul>
          {Array.from({ length: 5 }).map((_, i) => (
            <li key={i} className="flex items-center gap-4 px-5 py-3.5 border-b last:border-0">
              <Skeleton className="h-5 w-16 rounded-full" />
              <Skeleton className="h-4 flex-1" />
              <Skeleton className="h-3 w-12" />
            </li>
          ))}
        </ul>
      ) : !items || items.length === 0 ? (
        <p className="px-5 py-8 text-center text-xs text-muted-foreground">
          No activity recorded yet.
        </p>
      ) : (
        <ul>
          {items.map((item, i) => (
            <li
              key={item.id}
              className="flex items-center gap-4 px-5 py-3.5"
              style={i < items.length - 1 ? { boxShadow: '0 1px 0 var(--border)' } : undefined}
            >
              <Badge
                variant={item.statusGroup as 'active' | 'pending' | 'rejected'}
                className="shrink-0 text-[10px]"
              >
                {actionLabel(item.action)}
              </Badge>
              <p className="flex-1 text-sm text-foreground">
                <span className="font-medium">{item.entityType}</span>
                {' '}
                <span className="text-muted-foreground">{item.entityId}</span>
                {item.userName && item.userName !== 'System' && (
                  <span className="text-muted-foreground"> · {item.userName}</span>
                )}
              </p>
              <span className="shrink-0 text-xs text-muted-foreground">{item.timeAgo}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
