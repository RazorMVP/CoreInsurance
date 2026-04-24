import { lazy, Suspense } from 'react';
import { Route, Routes } from 'react-router-dom';
import { Skeleton } from '@cia/ui';

const ClaimsListPage    = lazy(() => import('./pages/ClaimsListPage'));
const ClaimDetailPage   = lazy(() => import('./pages/detail/ClaimDetailPage'));
const BulkClaimPage     = lazy(() => import('./pages/bulk/BulkClaimPage'));

function Loading() {
  return <div className="p-6 space-y-4"><Skeleton className="h-8 w-48" /><Skeleton className="h-64 w-full rounded-lg" /></div>;
}

export default function ClaimsModule() {
  return (
    <Suspense fallback={<Loading />}>
      <Routes>
        <Route index element={<ClaimsListPage />} />
        <Route path=":id" element={<ClaimDetailPage />} />
        <Route path="bulk" element={<BulkClaimPage />} />
      </Routes>
    </Suspense>
  );
}
