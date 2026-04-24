import { lazy, Suspense } from 'react';
import { Route, Routes } from 'react-router-dom';
import { Skeleton } from '@cia/ui';

const QuotationListPage = lazy(() => import('./pages/QuotationListPage'));
const QuoteDetailPage   = lazy(() => import('./pages/detail/QuoteDetailPage'));
const BulkUploadPage    = lazy(() => import('./pages/bulk/BulkUploadPage'));

function Loading() {
  return <div className="p-6 space-y-4"><Skeleton className="h-8 w-48" /><Skeleton className="h-64 w-full rounded-lg" /></div>;
}

export default function QuotationModule() {
  return (
    <Suspense fallback={<Loading />}>
      <Routes>
        <Route index element={<QuotationListPage />} />
        <Route path=":id" element={<QuoteDetailPage />} />
        <Route path="bulk-upload" element={<BulkUploadPage />} />
      </Routes>
    </Suspense>
  );
}
