import { lazy, Suspense } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';
import { Skeleton } from '@cia/ui';
import SetupLayout from './layout/SetupLayout';

const CompanySettingsPage     = lazy(() => import('./pages/company/CompanySettingsPage'));
const UsersPage               = lazy(() => import('./pages/users/UsersPage'));
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

export default function SetupModule() {
  return (
    <SetupLayout>
      <Suspense fallback={<PageLoading />}>
        <Routes>
          <Route index element={<Navigate to="company" replace />} />
          <Route path="company"          element={<CompanySettingsPage />} />
          <Route path="users"            element={<UsersPage />} />
          <Route path="access-groups"    element={<AccessGroupsPage />} />
          <Route path="approval-groups"  element={<ApprovalGroupsPage />} />
          <Route path="classes"               element={<ClassesPage />} />
          <Route path="products"              element={<ProductsPage />} />
          <Route path="policy-specifications" element={<PolicySpecificationsPage />} />
          <Route path="organisations"         element={<OrganisationsPage />} />
          <Route path="vehicle-registry" element={<VehicleRegistryPage />} />
          <Route path="claims-config"    element={<ClaimsConfigPage />} />
          <Route path="partner-apps"             element={<PartnerAppsPage />} />
          <Route path="customer-number-format"  element={<CustomerNumberFormatPage />} />
        </Routes>
      </Suspense>
    </SetupLayout>
  );
}
