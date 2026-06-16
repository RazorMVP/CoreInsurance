import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import type { ColumnDef } from '@tanstack/react-table';
import {
  Button, DataTable, DataTableColumnHeader, DataTableRowActions, PageHeader, PageSection,
  StatCard, Skeleton, toast,
} from '@cia/ui';
import {
  useTenants, usePlatformStats, useSuspendTenant, useActivateTenant,
  platformErrorCode, type TenantSummary,
} from '@cia/api-client';
import ServerPaginationFooter from '../../components/ServerPaginationFooter';
import ConfirmActionDialog from '../../components/ConfirmActionDialog';
import StatusBadge from './StatusBadge';
import OnboardTenantSheet from './OnboardTenantSheet';

export default function TenantsListPage() {
  const navigate = useNavigate();
  const [page, setPage] = useState(0);
  const size = 50;
  const tenantsQuery = useTenants(page, size);
  const statsQuery = usePlatformStats();
  const suspend = useSuspendTenant();
  const activate = useActivateTenant();

  const [onboardOpen, setOnboardOpen] = useState(false);
  const [pending, setPending] = useState<{ tenant: TenantSummary; action: 'suspend' | 'activate' } | null>(null);

  const rows = tenantsQuery.data?.data ?? [];
  const meta = tenantsQuery.data?.meta;
  const stats = statsQuery.data;

  async function runAction() {
    if (!pending) return;
    const { tenant, action } = pending;
    try {
      if (action === 'suspend') await suspend.mutateAsync(tenant.schema);
      else await activate.mutateAsync(tenant.schema);
      toast({ title: action === 'suspend' ? 'Tenant suspended' : 'Tenant activated', description: tenant.displayName });
      setPending(null);
    } catch (err) {
      toast({ variant: 'destructive', title: 'Action failed', description: platformErrorCode(err) ?? 'Unexpected error.' });
    }
  }

  const columns: ColumnDef<TenantSummary>[] = [
    { accessorKey: 'schema', header: ({ column }) => <DataTableColumnHeader column={column} title="Schema" />,
      cell: ({ row }) => <span className="font-mono text-xs">{row.original.schema}</span> },
    { accessorKey: 'displayName', header: ({ column }) => <DataTableColumnHeader column={column} title="Display name" /> },
    { accessorKey: 'subdomain', header: 'Subdomain' },
    { accessorKey: 'active', header: 'Status', cell: ({ row }) => <StatusBadge active={row.original.active} /> },
    { accessorKey: 'createdAt', header: 'Created',
      cell: ({ row }) => new Date(row.original.createdAt).toLocaleDateString() },
    {
      id: 'actions',
      cell: ({ row }) => {
        const t = row.original;
        return (
          <DataTableRowActions row={row} actions={[
            { label: 'View detail', onClick: () => navigate(`/tenants/${t.schema}`) },
            t.active
              ? { label: 'Suspend', onClick: () => setPending({ tenant: t, action: 'suspend' }), className: 'text-destructive' }
              : { label: 'Activate', onClick: () => setPending({ tenant: t, action: 'activate' }) },
          ]} />
        );
      },
    },
  ];

  return (
    <div className="p-6">
      <PageHeader
        title="Tenants"
        description="Cross-tenant lifecycle — onboard, suspend, activate."
        actions={<Button onClick={() => setOnboardOpen(true)}>+ Onboard tenant</Button>}
      />

      <div className="mt-4 grid grid-cols-3 gap-3">
        <StatCard label="Total" value={stats ? String(stats.total) : '—'} />
        <StatCard label="Active" value={stats ? String(stats.active) : '—'} />
        <StatCard label="Suspended" value={stats ? String(stats.suspended) : '—'} />
      </div>

      <PageSection className="mt-4">
        {tenantsQuery.isLoading ? (
          <Skeleton className="h-64 w-full" />
        ) : (
          <>
            <DataTable columns={columns} data={rows} />
            {meta && <ServerPaginationFooter page={page} size={size} total={meta.total} onPageChange={setPage} noun="tenants" />}
          </>
        )}
      </PageSection>

      <OnboardTenantSheet open={onboardOpen} onOpenChange={setOnboardOpen} />
      <ConfirmActionDialog
        open={!!pending}
        onOpenChange={(o) => !o && setPending(null)}
        title={pending?.action === 'suspend' ? 'Suspend tenant?' : 'Activate tenant?'}
        description={pending?.action === 'suspend'
          ? `Suspend ${pending?.tenant.displayName}? Its users are locked out at the gate immediately. Regulated data is retained — this is reversible.`
          : `Re-activate ${pending?.tenant.displayName}? Its users can sign in again.`}
        confirmLabel={pending?.action === 'suspend' ? 'Suspend' : 'Activate'}
        destructive={pending?.action === 'suspend'}
        busy={suspend.isPending || activate.isPending}
        onConfirm={runAction}
      />
    </div>
  );
}
