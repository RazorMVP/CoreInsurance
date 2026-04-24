import { cva, type VariantProps } from 'class-variance-authority';
import * as React from 'react';
import { cn } from '../lib/utils';

const badgeVariants = cva(
  'inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium transition-colors',
  {
    variants: {
      variant: {
        active:    'bg-[var(--status-active-bg)]    text-[var(--status-active-fg)]',
        pending:   'bg-[var(--status-pending-bg)]   text-[var(--status-pending-fg)]',
        rejected:  'bg-[var(--status-rejected-bg)]  text-[var(--status-rejected-fg)]',
        draft:     'bg-[var(--status-draft-bg)]     text-[var(--status-draft-fg)]',
        cancelled: 'bg-[var(--status-cancelled-bg)] text-[var(--status-cancelled-fg)]',
        default:   'bg-secondary text-secondary-foreground',
        outline:   'border border-border text-foreground',
      },
    },
    defaultVariants: { variant: 'default' },
  }
);

export interface BadgeProps
  extends React.HTMLAttributes<HTMLDivElement>,
    VariantProps<typeof badgeVariants> {}

function Badge({ className, variant, ...props }: BadgeProps) {
  return <div className={cn(badgeVariants({ variant }), className)} {...props} />;
}

export { Badge, badgeVariants };
