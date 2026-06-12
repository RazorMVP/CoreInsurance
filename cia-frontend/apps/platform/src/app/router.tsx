import { lazy, Suspense } from 'react';
import { createBrowserRouter, Navigate } from 'react-router-dom';
import AppShell from './layout/AppShell';
import SuperAdminGate from './SuperAdminGate';

const PlaceholderPage = lazy(() => import('../modules/_placeholder/PlaceholderPage'));
const TenantsListPage = lazy(() => import('../modules/tenants/TenantsListPage'));
const TenantDetailPage = lazy(() => import('../modules/tenants/TenantDetailPage'));
const AuditLogPage = lazy(() => import('../modules/audit/AuditLogPage'));

function Deferred({ children }: { children: React.ReactNode }) {
  return <Suspense fallback={<div className="p-6 text-sm text-muted-foreground">Loading…</div>}>{children}</Suspense>;
}

export const router = createBrowserRouter([
  {
    path: '/',
    element: <SuperAdminGate><AppShell /></SuperAdminGate>,
    children: [
      { index: true,            element: <Navigate to="/dashboard" replace /> },
      { path: 'dashboard',      element: <Deferred><PlaceholderPage title="Dashboard" /></Deferred> },
      { path: 'tenants',        element: <Deferred><TenantsListPage /></Deferred> },
      { path: 'tenants/:schema',element: <Deferred><TenantDetailPage /></Deferred> },
      { path: 'audit',          element: <Deferred><AuditLogPage /></Deferred> },
      { path: 'super-admins',   element: <Deferred><PlaceholderPage title="Super-admins" /></Deferred> },
    ],
  },
]);
