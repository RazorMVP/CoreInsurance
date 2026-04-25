import { Link } from 'react-router-dom';
import { Skeleton } from '@cia/ui';
import { HugeiconsIcon } from '@hugeicons/react';
import { Calendar01Icon } from '@hugeicons/core-free-icons';
import { cn } from '@cia/ui';
import type { RenewalDay } from '../hooks/useDashboard';

interface Props {
  days: RenewalDay[] | undefined;
  isLoading: boolean;
}

function urgencyStyle(count: number, isToday: boolean): string {
  if (isToday && count > 0) return 'bg-red-50 border-red-200 text-red-700';
  if (count > 5)  return 'bg-amber-50 border-amber-200 text-amber-700';
  if (count > 0)  return 'bg-blue-50 border-blue-200 text-blue-700';
  return 'bg-secondary border-border text-muted-foreground';
}

export default function RenewalsDueStrip({ days, isLoading }: Props) {
  const today = new Date().toISOString().slice(0, 10);
  const totalDue = days?.reduce((sum, d) => sum + d.count, 0) ?? 0;

  return (
    <div className="rounded-lg bg-card" style={{ boxShadow: '0 0 0 1px var(--border)' }}>
      <div
        className="flex items-center justify-between px-5 py-4"
        style={{ boxShadow: '0 1px 0 var(--border)' }}
      >
        <div className="flex items-center gap-2">
          <HugeiconsIcon icon={Calendar01Icon} size={16} color="var(--primary)" strokeWidth={1.75} />
          <h2 className="text-sm font-semibold text-foreground">Renewals Due — Next 7 Days</h2>
        </div>
        {!isLoading && (
          <Link
            to="/policies?filter=expiring"
            className="text-xs text-primary hover:underline"
          >
            {totalDue > 0 ? `View all ${totalDue} →` : 'View policies →'}
          </Link>
        )}
      </div>

      <div className="grid grid-cols-7 gap-2 p-4">
        {isLoading || !days ? (
          Array.from({ length: 7 }).map((_, i) => (
            <Skeleton key={i} className="h-16 rounded-lg" />
          ))
        ) : (
          days.map((day) => {
            const isToday = day.date === today;
            return (
              <Link
                key={day.date}
                to={`/policies?expiry=${day.date}`}
                className={cn(
                  'flex flex-col items-center justify-center rounded-lg border p-2 gap-1 transition-all hover:shadow-sm',
                  urgencyStyle(day.count, isToday)
                )}
              >
                <span className={cn(
                  'text-[10px] font-semibold uppercase tracking-wide',
                  isToday ? 'text-red-600' : 'text-muted-foreground'
                )}>
                  {isToday ? 'Today' : day.label}
                </span>
                <span className="text-lg font-bold tabular-nums leading-none">
                  {day.count}
                </span>
                <span className="text-[9px] text-muted-foreground">
                  {day.count === 1 ? 'policy' : 'policies'}
                </span>
              </Link>
            );
          })
        )}
      </div>

      {!isLoading && totalDue === 0 && (
        <p className="px-5 pb-4 text-xs text-muted-foreground text-center">
          No policies expiring in the next 7 days.
        </p>
      )}
    </div>
  );
}
