import { lazy, Suspense } from 'react';
import { Navigate, NavLink, Route, Routes } from 'react-router-dom';
import { cn, Skeleton } from '@cia/ui';

const PeriodLockListPage       = lazy(() => import('./pages/PeriodLockListPage'));
const ChartOfAccountsPage      = lazy(() => import('./pages/ChartOfAccountsPage'));
const PostingRulesPage         = lazy(() => import('./pages/PostingRulesPage'));
const JournalEntryBrowserPage  = lazy(() => import('./pages/JournalEntryBrowserPage'));
const TrialBalanceReportPage   = lazy(() => import('./pages/TrialBalanceReportPage'));
const BackfillAdminPage        = lazy(() => import('./pages/BackfillAdminPage'));
const PaaPeriodClosePage       = lazy(() => import('./pages/PaaPeriodClosePage'));
const PaaMovementAnalysisPage  = lazy(() => import('./pages/PaaMovementAnalysisPage'));
const ContractGroupsPage       = lazy(() => import('./pages/ContractGroupsPage'));
const HoldingsListPage         = lazy(() => import('./pages/HoldingsListPage'));
const Ifrs9MeasurementPage     = lazy(() => import('./pages/Ifrs9MeasurementPage'));
const Ifrs9MovementAnalysisPage = lazy(() => import('./pages/Ifrs9MovementAnalysisPage'));
const NaicomSubmissionsPage     = lazy(() => import('./pages/NaicomSubmissionsPage'));

const tabs: { label: string; path: string }[] = [
  { label: 'Periods',            path: '/closures/periods' },
  { label: 'Chart of Accounts',  path: '/closures/chart-of-accounts' },
  { label: 'Posting Rules',      path: '/closures/posting-rules' },
  { label: 'Journal Entries',    path: '/closures/journal-entries' },
  { label: 'Trial Balance',      path: '/closures/trial-balance' },
  { label: 'Backfill',           path: '/closures/backfill' },
  { label: 'PAA Close',          path: '/closures/paa-close' },
  { label: 'Movement Analysis',  path: '/closures/movement-analysis' },
  { label: 'Contract Groups',    path: '/closures/contract-groups' },
  { label: 'Holdings',           path: '/closures/holdings' },
  { label: 'IFRS 9 Measurement', path: '/closures/ifrs9-measurement' },
  { label: 'IFRS 9 §B5.5.39',    path: '/closures/ifrs9-movement-analysis' },
  { label: 'NAICOM',             path: '/closures/naicom' },
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
            <Route path="posting-rules"    element={<PostingRulesPage />} />
            <Route path="journal-entries"  element={<JournalEntryBrowserPage />} />
            <Route path="trial-balance"    element={<TrialBalanceReportPage />} />
            <Route path="backfill"         element={<BackfillAdminPage />} />
            <Route path="paa-close"           element={<PaaPeriodClosePage />} />
            <Route path="movement-analysis"   element={<PaaMovementAnalysisPage />} />
            <Route path="contract-groups"     element={<ContractGroupsPage />} />
            <Route path="holdings"            element={<HoldingsListPage />} />
            <Route path="ifrs9-measurement"          element={<Ifrs9MeasurementPage />} />
            <Route path="ifrs9-movement-analysis"    element={<Ifrs9MovementAnalysisPage />} />
            <Route path="naicom"                     element={<NaicomSubmissionsPage />} />
          </Routes>
        </Suspense>
      </div>
    </div>
  );
}
