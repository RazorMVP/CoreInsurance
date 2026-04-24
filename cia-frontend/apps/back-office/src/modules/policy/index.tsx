import { lazy, Suspense } from 'react';
import { Route, Routes } from 'react-router-dom';
import { Skeleton } from '@cia/ui';

const PolicyListPage   = lazy(() => import('./pages/PolicyListPage'));
const PolicyDetailPage = lazy(() => import('./pages/detail/PolicyDetailPage'));

function Loading() {
  return <div className="p-6 space-y-4"><Skeleton className="h-8 w-48" /><Skeleton className="h-64 w-full rounded-lg" /></div>;
}

export default function PolicyModule() {
  return (
    <Suspense fallback={<Loading />}>
      <Routes>
        <Route index element={<PolicyListPage />} />
        <Route path=":id" element={<PolicyDetailPage />} />
      </Routes>
    </Suspense>
  );
}
