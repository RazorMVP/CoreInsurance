import { lazy, Suspense } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';
import { Skeleton } from '@cia/ui';

const PeriodLockListPage = lazy(() => import('./pages/PeriodLockListPage'));

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
    <Suspense fallback={<Loading />}>
      <Routes>
        <Route index element={<Navigate to="periods" replace />} />
        <Route path="periods" element={<PeriodLockListPage />} />
      </Routes>
    </Suspense>
  );
}
