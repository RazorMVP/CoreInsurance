import { lazy, Suspense } from 'react';
import { createBrowserRouter, Navigate } from 'react-router-dom';
import AppShell from './layout/AppShell';

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

export const router = createBrowserRouter([
  {
    path: '/',
    element: <AppShell />,
    children: [
      { index: true,              element: <Navigate to="/dashboard" replace /> },
      { path: 'dashboard',        element: <Deferred><DashboardPage /></Deferred> },
      { path: 'setup/*',          element: <Deferred><SetupModule /></Deferred> },
      { path: 'customers/*',      element: <Deferred><CustomersModule /></Deferred> },
      { path: 'quotation/*',      element: <Deferred><QuotationModule /></Deferred> },
      { path: 'policies/*',       element: <Deferred><PolicyModule /></Deferred> },
      { path: 'endorsements/*',   element: <Deferred><EndorsementsModule /></Deferred> },
      { path: 'claims/*',         element: <Deferred><ClaimsModule /></Deferred> },
      { path: 'reinsurance/*',    element: <Deferred><ReinsuranceModule /></Deferred> },
      { path: 'finance/*',        element: <Deferred><FinanceModule /></Deferred> },
      { path: 'audit/*',          element: <Deferred><AuditModule /></Deferred> },
    ],
  },
]);
