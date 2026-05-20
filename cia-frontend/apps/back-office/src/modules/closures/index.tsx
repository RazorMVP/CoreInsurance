import { lazy, Suspense } from 'react';
import { Navigate, NavLink, Route, Routes } from 'react-router-dom';
import { cn, Skeleton } from '@cia/ui';

const PeriodLockListPage       = lazy(() => import('./pages/PeriodLockListPage'));
const ChartOfAccountsPage      = lazy(() => import('./pages/ChartOfAccountsPage'));
const JournalEntryBrowserPage  = lazy(() => import('./pages/JournalEntryBrowserPage'));
const TrialBalanceReportPage   = lazy(() => import('./pages/TrialBalanceReportPage'));

const tabs: { label: string; path: string }[] = [
  { label: 'Periods',           path: '/closures/periods' },
  { label: 'Chart of Accounts', path: '/closures/chart-of-accounts' },
  { label: 'Journal Entries',   path: '/closures/journal-entries' },
  { label: 'Trial Balance',     path: '/closures/trial-balance' },
];

function Loading() {
  return (
    <div className="p-6 space-y-4">
      <Skeleton className="h-8 w-56" />
      <Skeleton className="h-4 w-96" />
      <Skeleton className="h-72 w-full rounded-lg" />
    </div>
  );
}

export default function ClosuresModule() {
  return (
    <div className="flex h-full flex-col">
      <nav className="flex shrink-0 items-center gap-1 border-b bg-card px-6">
        {tabs.map((tab) => (
          <NavLink
            key={tab.path}
            to={tab.path}
            className={({ isActive }) =>
              cn(
                'relative px-3 py-3 text-sm font-medium transition-colors',
                isActive
                  ? 'text-primary after:absolute after:inset-x-0 after:-bottom-px after:h-0.5 after:bg-primary'
                  : 'text-muted-foreground hover:text-foreground',
              )
            }
          >
            {tab.label}
          </NavLink>
        ))}
      </nav>
      <div className="flex-1 overflow-y-auto">
        <Suspense fallback={<Loading />}>
          <Routes>
            <Route index element={<Navigate to="periods" replace />} />
            <Route path="periods"          element={<PeriodLockListPage />} />
            <Route path="chart-of-accounts" element={<ChartOfAccountsPage />} />
            <Route path="journal-entries"  element={<JournalEntryBrowserPage />} />
            <Route path="trial-balance"    element={<TrialBalanceReportPage />} />
          </Routes>
        </Suspense>
      </div>
    </div>
  );
}
