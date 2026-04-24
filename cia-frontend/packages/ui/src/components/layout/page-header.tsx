import * as React from 'react';
import { cn } from '../../lib/utils';

interface PageHeaderProps extends React.HTMLAttributes<HTMLDivElement> {
  title:        string;
  description?: string;
  actions?:     React.ReactNode;
  breadcrumb?:  React.ReactNode;
}

export function PageHeader({
  title,
  description,
  actions,
  breadcrumb,
  className,
  ...props
}: PageHeaderProps) {
  return (
    <div className={cn('space-y-1 pb-5', className)} {...props}>
      {breadcrumb && <div className="mb-2">{breadcrumb}</div>}
      <div className="flex items-start justify-between gap-4">
        <div className="space-y-0.5">
          <h2 className="font-display text-xl font-semibold tracking-tight text-foreground">
            {title}
          </h2>
          {description && (
            <p className="text-sm text-muted-foreground">{description}</p>
          )}
        </div>
        {actions && (
          <div className="flex shrink-0 items-center gap-2">{actions}</div>
        )}
      </div>
    </div>
  );
}
