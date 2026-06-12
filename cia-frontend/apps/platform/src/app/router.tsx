import { lazy, Suspense } from 'react';
import { createBrowserRouter, Navigate } from 'react-router-dom';
import AppShell from './layout/AppShell';
import SuperAdminGate from './SuperAdminGate';

const PlaceholderPage = lazy(() => import('../modules/_placeholder/PlaceholderPage'));

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
      { path: 'tenants',        element: <Deferred><PlaceholderPage title="Tenants" /></Deferred> },
      { path: 'tenants/:schema',element: <Deferred><PlaceholderPage title="Tenant detail" /></Deferred> },
      { path: 'audit',          element: <Deferred><PlaceholderPage title="Audit log" /></Deferred> },
      { path: 'super-admins',   element: <Deferred><PlaceholderPage title="Super-admins" /></Deferred> },
    ],
  },
]);
