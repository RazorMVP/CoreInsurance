import { useLocation } from 'react-router-dom';
import { HugeiconsIcon } from '@hugeicons/react';
import {
  Notification02Icon,
  HelpCircleIcon,
  Search01Icon,
} from '@hugeicons/core-free-icons';

const routeLabels: Record<string, string> = {
  dashboard:    'Dashboard',
  customers:    'Customers',
  quotation:    'Quotation',
  policies:     'Policies',
  endorsements: 'Endorsements',
  claims:       'Claims',
  finance:      'Finance',
  reinsurance:  'Reinsurance',
  setup:        'Setup & Administration',
  audit:        'Audit & Compliance',
};

export default function Topbar() {
  const location = useLocation();
  const segment  = location.pathname.split('/').filter(Boolean)[0] ?? 'dashboard';
  const label    = routeLabels[segment] ?? segment;

  return (
    <header
      className="flex h-[var(--topbar-height)] shrink-0 items-center gap-3 bg-card px-4"
      style={{ boxShadow: '0 1px 0 var(--border)' }}
    >
      {/* Page title */}
      <h1 className="shrink-0 font-display text-[15px] font-semibold tracking-tight text-foreground">
        {label}
      </h1>

      {/* Search */}
      <div className="relative flex flex-1 items-center">
        <span className="pointer-events-none absolute left-3 text-muted-foreground">
          <HugeiconsIcon icon={Search01Icon} size={15} color="currentColor" strokeWidth={1.75} />
        </span>
        <input
          type="search"
          placeholder="Search policies, claims, customers…"
          className="h-[37px] w-full rounded-md bg-background pl-9 pr-4 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
          style={{ border: '1px solid var(--border)' }}
        />
      </div>

      {/* Right icons */}
      <div className="flex shrink-0 items-center gap-1">
        <button
          className="flex h-9 w-9 items-center justify-center rounded-md text-muted-foreground hover:bg-secondary hover:text-foreground transition-colors"
          aria-label="Notifications"
        >
          <HugeiconsIcon icon={Notification02Icon} size={18} color="currentColor" strokeWidth={1.75} />
        </button>
        <button
          className="flex h-9 w-9 items-center justify-center rounded-md text-muted-foreground hover:bg-secondary hover:text-foreground transition-colors"
          aria-label="Help"
        >
          <HugeiconsIcon icon={HelpCircleIcon} size={18} color="currentColor" strokeWidth={1.75} />
        </button>
      </div>
    </header>
  );
}
