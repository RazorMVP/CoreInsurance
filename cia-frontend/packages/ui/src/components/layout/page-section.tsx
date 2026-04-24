import * as React from 'react';
import { cn } from '../../lib/utils';

interface PageSectionProps extends React.HTMLAttributes<HTMLDivElement> {
  title?:       string;
  description?: string;
  actions?:     React.ReactNode;
}

export function PageSection({
  title,
  description,
  actions,
  children,
  className,
  ...props
}: PageSectionProps) {
  return (
    <section className={cn('space-y-4', className)} {...props}>
      {(title || actions) && (
        <div className="flex items-center justify-between">
          <div className="space-y-0.5">
            {title       && <h3 className="font-display text-base font-semibold text-foreground">{title}</h3>}
            {description && <p className="text-sm text-muted-foreground">{description}</p>}
          </div>
          {actions && <div className="flex items-center gap-2">{actions}</div>}
        </div>
      )}
      {children}
    </section>
  );
}
