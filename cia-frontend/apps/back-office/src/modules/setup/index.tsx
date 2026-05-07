import { lazy, Suspense, type ReactNode } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';
import { useAuth } from '@cia/auth';
import { Skeleton } from '@cia/ui';
import { SETUP_ROUTE_ACCESS } from '../../app/security/access-control';
import RequireAccess from '../../app/security/RequireAccess';
import SetupLayout from './layout/SetupLayout';

const CompanySettingsPage     = lazy(() => import('./pages/company/CompanySettingsPage'));
const AccessGroupsPage        = lazy(() => import('./pages/access-groups/AccessGroupsPage'));
const ApprovalGroupsPage      = lazy(() => import('./pages/approval-groups/ApprovalGroupsPage'));
const ClassesPage             = lazy(() => import('./pages/classes/ClassesPage'));
const ProductsPage            = lazy(() => import('./pages/products/ProductsPage'));
const PolicySpecificationsPage = lazy(() => import('./pages/policy-specs/PolicySpecificationsPage'));
const OrganisationsPage       = lazy(() => import('./pages/organisations/OrganisationsPage'));
const VehicleRegistryPage     = lazy(() => import('./pages/vehicle-registry/VehicleRegistryPage'));
const ClaimsConfigPage        = lazy(() => import('./pages/claims-config/ClaimsConfigPage'));
const PartnerAppsPage              = lazy(() => import('./pages/partner-apps/PartnerAppsPage'));
const CustomerNumberFormatPage     = lazy(() => import('./pages/customer-number-format/CustomerNumberFormatPage'));

function PageLoading() {
  return (
    <div className="space-y-4 p-6">
      <Skeleton className="h-8 w-48" />
      <Skeleton className="h-4 w-96" />
      <Skeleton className="mt-6 h-64 w-full rounded-lg" />
    </div>
  );
}

function SetupIndexRedirect() {
  const { hasAnyAuthority } = useAuth();
  const firstAccessiblePath = Object.entries(SETUP_ROUTE_ACCESS)
    .find(([, access]) => hasAnyAuthority(access))?.[0];

  return (
    <Navigate
      to={firstAccessiblePath ? firstAccessiblePath.replace('/setup/', '') : '/dashboard'}
      replace
    />
  );
}

function SetupPage({ path, children }: { path: keyof typeof SETUP_ROUTE_ACCESS; children: ReactNode }) {
  return (
    <RequireAccess anyAuthority={SETUP_ROUTE_ACCESS[path]}>
      {children}
    </RequireAccess>
  );
}

export default function SetupModule() {
  return (
    <SetupLayout>
      <Suspense fallback={<PageLoading />}>
        <Routes>
          <Route index element={<SetupIndexRedirect />} />
          <Route path="company" element={<SetupPage path="/setup/company"><CompanySettingsPage /></SetupPage>} />
          <Route path="access-groups" element={<SetupPage path="/setup/access-groups"><AccessGroupsPage /></SetupPage>} />
          <Route path="approval-groups" element={<SetupPage path="/setup/approval-groups"><ApprovalGroupsPage /></SetupPage>} />
          <Route path="classes" element={<SetupPage path="/setup/classes"><ClassesPage /></SetupPage>} />
          <Route path="products" element={<SetupPage path="/setup/products"><ProductsPage /></SetupPage>} />
          <Route path="policy-specifications" element={<SetupPage path="/setup/policy-specifications"><PolicySpecificationsPage /></SetupPage>} />
          <Route path="organisations" element={<SetupPage path="/setup/organisations"><OrganisationsPage /></SetupPage>} />
          <Route path="vehicle-registry" element={<SetupPage path="/setup/vehicle-registry"><VehicleRegistryPage /></SetupPage>} />
          <Route path="claims-config" element={<SetupPage path="/setup/claims-config"><ClaimsConfigPage /></SetupPage>} />
          <Route path="partner-apps" element={<SetupPage path="/setup/partner-apps"><PartnerAppsPage /></SetupPage>} />
          <Route path="customer-number-format" element={<SetupPage path="/setup/customer-number-format"><CustomerNumberFormatPage /></SetupPage>} />
        </Routes>
      </Suspense>
    </SetupLayout>
  );
}
