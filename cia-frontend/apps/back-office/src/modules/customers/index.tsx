import { lazy, Suspense } from 'react';
import { Route, Routes } from 'react-router-dom';
import { Skeleton } from '@cia/ui';

const CustomersListPage     = lazy(() => import('./pages/CustomersListPage'));
const CustomerDetailPage    = lazy(() => import('./pages/detail/CustomerDetailPage'));
const LossRatioReportPage   = lazy(() => import('./pages/reports/LossRatioReportPage'));
const ActiveCustomersReport = lazy(() => import('./pages/reports/ActiveCustomersReportPage'));

function PageLoading() {
  return (
    <div className="space-y-4 p-6">
      <Skeleton className="h-8 w-48" />
      <Skeleton className="h-4 w-96" />
      <Skeleton className="mt-6 h-64 w-full rounded-lg" />
    </div>
  );
}

export default function CustomersModule() {
  return (
    <Suspense fallback={<PageLoading />}>
      <Routes>
        <Route index element={<CustomersListPage />} />
        <Route path=":id" element={<CustomerDetailPage />} />
        <Route path="reports/loss-ratio" element={<LossRatioReportPage />} />
        <Route path="reports/active" element={<ActiveCustomersReport />} />
      </Routes>
    </Suspense>
  );
}
