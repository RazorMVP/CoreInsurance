import { lazy, Suspense } from 'react';
import { Route, Routes } from 'react-router-dom';
import { Skeleton } from '@cia/ui';

const FinancePage = lazy(() => import('./pages/FinancePage'));

function Loading() {
  return <div className="p-6 space-y-4"><Skeleton className="h-8 w-48" /><Skeleton className="h-64 w-full rounded-lg" /></div>;
}

export default function FinanceModule() {
  return (
    <Suspense fallback={<Loading />}>
      <Routes>
        <Route index element={<FinancePage />} />
      </Routes>
    </Suspense>
  );
}
