import { useRef, useState } from 'react';
import { useLocation } from 'react-router-dom';
import { HugeiconsIcon } from '@hugeicons/react';
import {
  Notification02Icon,
  HelpCircleIcon,
  Shield01Icon,
  NoteEditIcon,
  FileEditIcon,
  AlertCircleIcon,
  Money01Icon,
} from '@hugeicons/core-free-icons';
import { Badge } from '@cia/ui';
import { useApprovalQueue } from '../../modules/dashboard/hooks/useDashboard';
import { useClickOutside } from '../../hooks/useClickOutside';
import SearchBar from './SearchBar';

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
  reports:      'Reports & Analytics',
};

const QUEUE_ITEMS = [
  { label: 'Policies',     key: 'policies' as const,     icon: Shield01Icon,  path: '/policies' },
  { label: 'Quotes',       key: 'quotes' as const,       icon: NoteEditIcon,  path: '/quotation' },
  { label: 'Endorsements', key: 'endorsements' as const, icon: FileEditIcon,  path: '/endorsements' },
  { label: 'Claims',       key: 'claims' as const,       icon: AlertCircleIcon, path: '/claims' },
  { label: 'Receipts',     key: 'receipts' as const,     icon: Money01Icon,   path: '/finance' },
  { label: 'Payments',     key: 'payments' as const,     icon: Money01Icon,   path: '/finance' },
];

export default function Topbar() {
  const location = useLocation();
  const segment  = location.pathname.split('/').filter(Boolean)[0] ?? 'dashboard';
  const label    = routeLabels[segment] ?? segment;

  const [notifOpen, setNotifOpen] = useState(false);
  const notifRef = useRef<HTMLDivElement>(null);
  useClickOutside(notifRef, () => setNotifOpen(false));

  const { data: queue } = useApprovalQueue();
  const totalPending = queue
    ? queue.policies + queue.quotes + queue.endorsements + queue.claims + queue.receipts + queue.payments
    : 0;

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
      <SearchBar />

      {/* Right icons */}
      <div className="flex shrink-0 items-center gap-1">

        {/* Notification bell with approval-queue badge */}
        <div ref={notifRef} className="relative">
          <button
            onClick={() => setNotifOpen(o => !o)}
            className="relative flex h-9 w-9 items-center justify-center rounded-md text-muted-foreground hover:bg-secondary hover:text-foreground transition-colors"
            aria-label="Notifications"
          >
            <HugeiconsIcon icon={Notification02Icon} size={18} color="currentColor" strokeWidth={1.75} />
            {totalPending > 0 && (
              <span className="absolute -right-0.5 -top-0.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-destructive px-1 text-[9px] font-bold text-destructive-foreground">
                {totalPending > 99 ? '99+' : totalPending}
              </span>
            )}
          </button>

          {/* Notification dropdown */}
          {notifOpen && (
            <div
              className="absolute right-0 top-11 z-50 w-72 rounded-lg border bg-card shadow-lg"
              style={{ boxShadow: '0 4px 24px rgba(0,0,0,0.10)' }}
            >
              <div className="flex items-center justify-between border-b px-4 py-3">
                <p className="text-sm font-semibold text-foreground">Pending Approvals</p>
                {totalPending > 0 && (
                  <Badge variant="pending" className="text-[10px]">{totalPending} total</Badge>
                )}
              </div>

              {totalPending === 0 ? (
                <p className="px-4 py-6 text-center text-xs text-muted-foreground">
                  No pending approvals — all clear.
                </p>
              ) : (
                <ul className="divide-y divide-border py-1">
                  {QUEUE_ITEMS.filter(item => queue && queue[item.key] > 0).map(item => (
                    <li key={item.key}>
                      <a
                        href={item.path}
                        onClick={() => setNotifOpen(false)}
                        className="flex items-center justify-between px-4 py-2.5 hover:bg-secondary/40 transition-colors"
                      >
                        <div className="flex items-center gap-2.5">
                          <HugeiconsIcon icon={item.icon} size={14} color="currentColor" strokeWidth={1.75} className="text-muted-foreground" />
                          <span className="text-sm text-foreground">{item.label}</span>
                        </div>
                        <Badge variant="pending" className="text-[10px] tabular-nums">
                          {queue![item.key]}
                        </Badge>
                      </a>
                    </li>
                  ))}
                </ul>
              )}

              <div className="border-t px-4 py-2.5">
                <a
                  href="/dashboard"
                  onClick={() => setNotifOpen(false)}
                  className="text-xs text-primary hover:underline"
                >
                  View full approval queue →
                </a>
              </div>
            </div>
          )}
        </div>

        {/* Help — URL controlled by VITE_HELP_URL env var; falls back to Confluence PRD */}
        <a
          href={import.meta.env.VITE_HELP_URL ?? 'https://akinwalenubeero.atlassian.net/wiki/spaces/CIAGB/overview'}
          target="_blank"
          rel="noopener noreferrer"
          className="flex h-9 w-9 items-center justify-center rounded-md text-muted-foreground hover:bg-secondary hover:text-foreground transition-colors"
          aria-label="Help & Documentation"
          title="Help & Documentation"
        >
          <HugeiconsIcon icon={HelpCircleIcon} size={18} color="currentColor" strokeWidth={1.75} />
        </a>
      </div>
    </header>
  );
}
