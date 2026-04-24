import { lazy, Suspense } from 'react';
import { Route, Routes } from 'react-router-dom';
import { Skeleton } from '@cia/ui';

const EndorsementsListPage   = lazy(() => import('./pages/EndorsementsListPage'));
const EndorsementDetailPage  = lazy(() => import('./pages/detail/EndorsementDetailPage'));
const DebitNoteAnalysisPage  = lazy(() => import('./pages/reports/DebitNoteAnalysisPage'));

function Loading() {
  return <div className="p-6 space-y-4"><Skeleton className="h-8 w-48" /><Skeleton className="h-64 w-full rounded-lg" /></div>;
}

export default function EndorsementsModule() {
  return (
    <Suspense fallback={<Loading />}>
      <Routes>
        <Route index element={<EndorsementsListPage />} />
        <Route path=":id" element={<EndorsementDetailPage />} />
        <Route path="reports/debit-note-analysis" element={<DebitNoteAnalysisPage />} />
      </Routes>
    </Suspense>
  );
}
