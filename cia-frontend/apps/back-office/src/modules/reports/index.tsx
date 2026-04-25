import { lazy, Suspense } from 'react';
import { Route, Routes } from 'react-router-dom';
import { Skeleton } from '@cia/ui';

const ReportsHomePage        = lazy(() => import('./pages/home/ReportsHomePage'));
const ReportLibraryPage      = lazy(() => import('./pages/library/ReportLibraryPage'));
const CustomReportBuilderPage = lazy(() => import('./pages/builder/CustomReportBuilderPage'));
const ReportViewerPage       = lazy(() => import('./pages/viewer/ReportViewerPage'));
const ReportAccessSetupPage  = lazy(() => import('./pages/setup/ReportAccessSetupPage'));

function PageLoading() {
  return (
    <div className="space-y-4 p-6">
      <Skeleton className="h-8 w-48" />
      <Skeleton className="h-4 w-96" />
      <Skeleton className="mt-6 h-64 w-full rounded-lg" />
    </div>
  );
}

export default function ReportsModule() {
  return (
    <Suspense fallback={<PageLoading />}>
      <Routes>
        <Route index element={<ReportsHomePage />} />
        <Route path="library"     element={<ReportLibraryPage />} />
        <Route path="custom"      element={<CustomReportBuilderPage />} />
        <Route path="custom/:id"  element={<CustomReportBuilderPage />} />
        <Route path="run/:id"     element={<ReportViewerPage />} />
        <Route path="setup"       element={<ReportAccessSetupPage />} />
      </Routes>
    </Suspense>
  );
}
