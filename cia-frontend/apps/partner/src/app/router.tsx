import { lazy, Suspense } from 'react';
import { createBrowserRouter, Navigate } from 'react-router-dom';
import { AppShell } from './layout/AppShell';

const UsagePage = lazy(() => import('../modules/usage/UsagePage'));
const CredentialsPage = lazy(() => import('../modules/credentials/CredentialsPage'));
const ExplorerPage = lazy(() => import('../modules/explorer/ExplorerPage'));
const WebhooksPage = lazy(() => import('../modules/webhooks/WebhooksPage'));

function PageSkeleton() {
  return (
    <div className="flex flex-col gap-4 animate-pulse">
      <div className="h-8 w-48 rounded bg-muted" />
      <div className="h-4 w-96 rounded bg-muted" />
      <div className="h-64 rounded bg-muted" />
    </div>
  );
}
const D = ({ children }: { children: React.ReactNode }) => <Suspense fallback={<PageSkeleton />}>{children}</Suspense>;

export const router = createBrowserRouter([
  {
    path: '/',
    element: <AppShell />,
    children: [
      { index: true, element: <Navigate to="/usage" replace /> },
      { path: 'usage', element: <D><UsagePage /></D> },
      { path: 'credentials', element: <D><CredentialsPage /></D> },
      { path: 'explorer', element: <D><ExplorerPage /></D> },
      { path: 'webhooks', element: <D><WebhooksPage /></D> },
    ],
  },
]);
