import { useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Button, PageHeader, PageSection, Skeleton, EmptyState, toast } from '@cia/ui';
import {
  useTenantDetail, useSuspendTenant, useActivateTenant, platformErrorCode,
} from '@cia/api-client';
import StatusBadge from './StatusBadge';
import AuditTable from '../audit/AuditTable';
import ConfirmActionDialog from '../../components/ConfirmActionDialog';

export default function TenantDetailPage() {
  const { schema } = useParams<{ schema: string }>();
  const navigate = useNavigate();
  const detailQuery = useTenantDetail(schema);
  const suspend = useSuspendTenant();
  const activate = useActivateTenant();
  const [confirm, setConfirm] = useState(false);

  if (detailQuery.isLoading) return <div className="p-6"><Skeleton className="h-64 w-full" /></div>;
  if (detailQuery.isError || !detailQuery.data) {
    return (
      <div className="p-6">
        <EmptyState
          title="Tenant not found"
          description={`No tenant with schema "${schema}".`}
          action={<Button variant="outline" onClick={() => navigate('/tenants')}>Back to tenants</Button>}
        />
      </div>
    );
  }

  const { tenant, recentAudit } = detailQuery.data;
  const action = tenant.active ? 'suspend' : 'activate';

  async function run() {
    try {
      if (action === 'suspend') await suspend.mutateAsync(tenant.schema);
      else await activate.mutateAsync(tenant.schema);
      toast({ title: action === 'suspend' ? 'Tenant suspended' : 'Tenant activated', description: tenant.displayName });
      setConfirm(false);
    } catch (err) {
      toast({ variant: 'destructive', title: 'Action failed', description: platformErrorCode(err) ?? 'Unexpected error.' });
    }
  }

  return (
    <div className="p-6">
      <PageHeader
        title={tenant.displayName}
        description={`${tenant.schema} · ${tenant.subdomain}`}
        actions={
          <Button variant={tenant.active ? 'destructive' : 'default'} onClick={() => setConfirm(true)}>
            {tenant.active ? 'Suspend' : 'Activate'}
          </Button>
        }
      />

      <div className="mt-3 flex items-center gap-3 text-sm">
        <StatusBadge active={tenant.active} />
        <span className="text-muted-foreground">Created {new Date(tenant.createdAt).toLocaleDateString()}</span>
      </div>

      <PageSection title="Recent activity" className="mt-6">
        <AuditTable rows={recentAudit} />
      </PageSection>

      <ConfirmActionDialog
        open={confirm}
        onOpenChange={setConfirm}
        title={tenant.active ? 'Suspend tenant?' : 'Activate tenant?'}
        description={tenant.active
          ? `Suspend ${tenant.displayName}? Its users are locked out at the gate immediately. Regulated data is retained — reversible.`
          : `Re-activate ${tenant.displayName}? Its users can sign in again.`}
        confirmLabel={tenant.active ? 'Suspend' : 'Activate'}
        destructive={tenant.active}
        busy={suspend.isPending || activate.isPending}
        onConfirm={run}
      />
    </div>
  );
}
