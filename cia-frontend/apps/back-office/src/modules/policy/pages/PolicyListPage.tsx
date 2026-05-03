import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Badge, Button, DataTable, DataTableColumnHeader, DataTableRowActions,
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger,
  EmptyState, PageHeader, Skeleton,
} from '@cia/ui';
import { type ColumnDef } from '@tanstack/react-table';
import { useQuery } from '@tanstack/react-query';
import { apiClient, type PolicyDto } from '@cia/api-client';
import CreatePolicySheet from './create/CreatePolicySheet';

const statusVariant: Record<PolicyDto['status'], 'active' | 'pending' | 'draft' | 'cancelled' | 'rejected'> = {
  ACTIVE:           'active',
  PENDING_APPROVAL: 'pending',
  DRAFT:            'draft',
  EXPIRED:          'cancelled',
  CANCELLED:        'rejected',
  LAPSED:           'draft',
};

function NaicomBadge({ uid }: { uid?: string }) {
  if (!uid)  return <Badge variant="pending" className="text-[10px] font-mono">PENDING</Badge>;
  return <span className="font-mono text-xs text-foreground">{uid}</span>;
}

export default function PolicyListPage() {
  const navigate = useNavigate();
  const [createOpen, setCreateOpen] = useState(false);

  const policiesQuery = useQuery<PolicyDto[]>({
    queryKey: ['policies'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: PolicyDto[] }>('/api/v1/policies');
      return res.data.data;
    },
  });
  const policies = policiesQuery.data ?? [];

  const columns: ColumnDef<PolicyDto>[] = [
    {
      accessorKey: 'policyNumber',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Policy No." />,
      cell: ({ row }) => (
        <button
          className="font-mono text-xs text-primary hover:underline"
          onClick={() => navigate(`/policies/${row.original.id}`)}
        >
          {row.original.policyNumber}
        </button>
      ),
    },
    {
      accessorKey: 'customerName',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Customer" />,
      cell: ({ getValue }) => <span className="text-sm font-medium">{getValue() as string}</span>,
    },
    {
      accessorKey: 'productName',
      header: 'Product / Class',
      cell: ({ row }) => (
        <div>
          <p className="text-sm">{row.original.productName}</p>
          <p className="text-xs text-muted-foreground">{row.original.classOfBusinessName}</p>
        </div>
      ),
    },
    {
      accessorKey: 'sumInsured',
      header: 'Sum Insured',
      cell: ({ getValue }) => (
        <span className="text-sm tabular-nums">₦{(getValue() as number).toLocaleString()}</span>
      ),
    },
    {
      accessorKey: 'netPremium',
      header: 'Net Premium',
      cell: ({ getValue }) => (
        <span className="text-sm font-medium tabular-nums">₦{(getValue() as number).toLocaleString()}</span>
      ),
    },
    {
      accessorKey: 'status',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Status" />,
      cell: ({ getValue }) => {
        const s = getValue() as PolicyDto['status'];
        return <Badge variant={statusVariant[s]}>{s.toLowerCase().replace('_', ' ')}</Badge>;
      },
    },
    {
      accessorKey: 'naicomUid',
      header: 'NAICOM UID',
      cell: ({ getValue }) => <NaicomBadge uid={getValue() as string | undefined} />,
    },
    {
      accessorKey: 'endDate',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Expiry" />,
      cell: ({ getValue }) => (
        <span className="text-sm text-muted-foreground">{getValue() as string}</span>
      ),
    },
    {
      id: 'actions',
      cell: ({ row }) => {
        const { status, id } = row.original;
        return (
          <DataTableRowActions
            row={row}
            actions={[
              { label: 'View details',         onClick: () => navigate(`/policies/${id}`) },
              ...(status === 'DRAFT'            ? [{ label: 'Submit for approval', onClick: () => {} }] : []),
              ...(status === 'ACTIVE'           ? [{ label: 'Add endorsement',     onClick: () => {} }] : []),
              ...(status === 'ACTIVE'           ? [{ label: 'Register claim',      onClick: () => {} }] : []),
              ...(status === 'PENDING_APPROVAL' ? [{ label: 'Approve policy',      onClick: () => {} }] : []),
              { label: 'Download document',    onClick: () => {}, separator: status !== 'DRAFT' },
            ].filter(Boolean)}
          />
        );
      },
    },
  ];

  return (
    <div className="p-6 space-y-5">
      <PageHeader
        title="Policies"
        description="Manage the full policy lifecycle from issuance through renewal."
        actions={
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button>New Policy ▾</Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end">
              <DropdownMenuItem onClick={() => setCreateOpen(true)}>
                Convert from approved quote
              </DropdownMenuItem>
              <DropdownMenuItem onClick={() => setCreateOpen(true)}>
                Create without quote
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        }
      />

      {policiesQuery.isLoading ? (
        <div className="space-y-3"><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /></div>
      ) : policies.length === 0 ? (
        <EmptyState
          title="No policies yet"
          description="Issue your first policy by converting an approved quote or creating one directly."
          action={<Button onClick={() => setCreateOpen(true)}>New Policy</Button>}
        />
      ) : (
        <DataTable
          columns={columns}
          data={policies}
          toolbar={{ searchColumn: 'customerName', searchPlaceholder: 'Search by customer…' }}
        />
      )}

      <CreatePolicySheet open={createOpen} onOpenChange={setCreateOpen} onSuccess={() => setCreateOpen(false)} />
    </div>
  );
}
