import * as React from 'react';
import { cn } from '../../lib/utils';

interface StatCardProps extends React.HTMLAttributes<HTMLDivElement> {
  label:       string;
  value:       React.ReactNode;
  sub?:        React.ReactNode;
  icon?:       React.ReactNode;
  trend?:      'up' | 'down' | 'neutral';
}

export function StatCard({ label, value, sub, icon, className, ...props }: StatCardProps) {
  return (
    <div
      className={cn(
        'rounded-lg border bg-card p-5 space-y-2',
        className,
      )}
      {...props}
    >
      <div className="flex items-center justify-between">
        <p className="text-xs font-medium text-muted-foreground">{label}</p>
        {icon && <div className="text-muted-foreground">{icon}</div>}
      </div>
      <p className="font-display text-2xl font-semibold tracking-tight text-foreground leading-none">
        {value}
      </p>
      {sub && <p className="text-xs text-muted-foreground">{sub}</p>}
    </div>
  );
}
