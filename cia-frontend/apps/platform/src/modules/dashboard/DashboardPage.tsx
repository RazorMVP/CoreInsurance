import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button, PageHeader, PageSection, StatCard, Skeleton } from '@cia/ui';
import { usePlatformStats, usePlatformAudit } from '@cia/api-client';
import AuditTable from '../audit/AuditTable';
import OnboardTenantSheet from '../tenants/OnboardTenantSheet';

export default function DashboardPage() {
  const navigate = useNavigate();
  const stats = usePlatformStats().data;
  const auditQuery = usePlatformAudit(0, 8);
  const [onboardOpen, setOnboardOpen] = useState(false);

  return (
    <div className="p-6">
      <PageHeader
        title="Platform overview"
        description="Cross-tenant health and recent activity."
        actions={
          <div className="flex gap-2">
            <Button variant="outline" onClick={() => navigate('/super-admins?invite=1')}>Invite super-admin</Button>
            <Button onClick={() => setOnboardOpen(true)}>+ Onboard tenant</Button>
          </div>
        }
      />

      <div className="mt-4 grid grid-cols-3 gap-3">
        <StatCard label="Total tenants" value={stats ? String(stats.total) : '—'} />
        <StatCard label="Active" value={stats ? String(stats.active) : '—'} />
        <StatCard label="Suspended" value={stats ? String(stats.suspended) : '—'} />
      </div>

      <PageSection title="Recent activity" className="mt-6">
        {auditQuery.isLoading
          ? <Skeleton className="h-48 w-full" />
          : <AuditTable rows={auditQuery.data?.data ?? []} />}
      </PageSection>

      <OnboardTenantSheet open={onboardOpen} onOpenChange={setOnboardOpen} />
    </div>
  );
}
