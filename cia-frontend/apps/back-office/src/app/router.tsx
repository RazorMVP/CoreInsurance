import { lazy, Suspense, type ReactNode } from 'react';
import { createBrowserRouter, Navigate } from 'react-router-dom';
import AppShell from './layout/AppShell';
import { MODULE_ACCESS } from './security/access-control';
import RequireAccess from './security/RequireAccess';

const DashboardPage     = lazy(() => import('../modules/dashboard/DashboardPage'));
const SetupModule       = lazy(() => import('../modules/setup'));
const CustomersModule   = lazy(() => import('../modules/customers'));
const QuotationModule   = lazy(() => import('../modules/quotation'));
const PolicyModule      = lazy(() => import('../modules/policy'));
const EndorsementsModule= lazy(() => import('../modules/endorsements'));
const ClaimsModule      = lazy(() => import('../modules/claims'));
const ReinsuranceModule = lazy(() => import('../modules/reinsurance'));
const FinanceModule     = lazy(() => import('../modules/finance'));
const AuditModule       = lazy(() => import('../modules/audit'));
const ReportsModule     = lazy(() => import('../modules/reports'));

function Deferred({ children }: { children: React.ReactNode }) {
  return <Suspense fallback={<PageSkeleton />}>{children}</Suspense>;
}

function PageSkeleton() {
  return (
    <div className="flex flex-col gap-4 p-6 animate-pulse">
      <div className="h-8 w-48 rounded-md bg-muted" />
      <div className="h-4 w-96 rounded-md bg-muted" />
      <div className="mt-4 h-64 rounded-lg bg-muted" />
    </div>
  );
}

function Protected({ access, children }: { access: readonly string[]; children: ReactNode }) {
  return (
    <RequireAccess anyAuthority={access}>
      <Deferred>{children}</Deferred>
    </RequireAccess>
  );
}

export const router = createBrowserRouter([
  {
    path: '/',
    element: <AppShell />,
    children: [
      { index: true,              element: <Navigate to="/dashboard" replace /> },
      { path: 'dashboard',        element: <Protected access={MODULE_ACCESS.dashboard}><DashboardPage /></Protected> },
      { path: 'setup/*',          element: <Protected access={MODULE_ACCESS.setup}><SetupModule /></Protected> },
      { path: 'customers/*',      element: <Protected access={MODULE_ACCESS.customers}><CustomersModule /></Protected> },
      { path: 'quotation/*',      element: <Protected access={MODULE_ACCESS.quotation}><QuotationModule /></Protected> },
      { path: 'policies/*',       element: <Protected access={MODULE_ACCESS.policies}><PolicyModule /></Protected> },
      { path: 'endorsements/*',   element: <Protected access={MODULE_ACCESS.endorsements}><EndorsementsModule /></Protected> },
      { path: 'claims/*',         element: <Protected access={MODULE_ACCESS.claims}><ClaimsModule /></Protected> },
      { path: 'reinsurance/*',    element: <Protected access={MODULE_ACCESS.reinsurance}><ReinsuranceModule /></Protected> },
      { path: 'finance/*',        element: <Protected access={MODULE_ACCESS.finance}><FinanceModule /></Protected> },
      { path: 'audit/*',          element: <Protected access={MODULE_ACCESS.audit}><AuditModule /></Protected> },
      { path: 'reports/*',        element: <Protected access={MODULE_ACCESS.reports}><ReportsModule /></Protected> },
    ],
  },
]);
