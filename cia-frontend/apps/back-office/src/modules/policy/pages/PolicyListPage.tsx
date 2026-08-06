import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Badge, Button, DataTable, DataTableColumnHeader, DataTableRowActions,
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger,
  EmptyState, PageHeader,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  Skeleton,
} from '@cia/ui';
import { type ColumnDef } from '@tanstack/react-table';
import { useQuery } from '@tanstack/react-query';
import { validatedList, PolicySummaryDtoSchema, type PolicySummaryDto } from '@cia/api-client';
import { useServerPagination } from '@/lib/use-server-pagination';
import { useDebouncedValue } from '@/lib/use-debounced-value';
import CreatePolicySheet from './create/CreatePolicySheet';

const POLICY_STATUSES = ['DRAFT', 'PENDING_APPROVAL', 'ACTIVE', 'REINSTATED', 'EXPIRED', 'CANCELLED', 'REJECTED', 'LAPSED'] as const;

const statusVariant: Record<PolicySummaryDto['status'], 'active' | 'pending' | 'draft' | 'cancelled' | 'rejected'> = {
  ACTIVE:           'active',
  REINSTATED:       'active',
  PENDING_APPROVAL: 'pending',
  DRAFT:            'draft',
  EXPIRED:          'cancelled',
  CANCELLED:        'rejected',
  REJECTED:         'rejected',
  LAPSED:           'draft',
};

function NaicomBadge({ uid }: { uid?: string }) {
  if (!uid)  return <Badge variant="pending" className="text-[10px] font-mono">PENDING</Badge>;
  return <span className="font-mono text-xs text-foreground">{uid}</span>;
}

export default function PolicyListPage() {
  const navigate = useNavigate();
  const [createOpen, setCreateOpen] = useState(false);

  const { page, size, sort, filters, setPage, setSize, setSort, setFilter } =
    useServerPagination({ defaultSize: 20, defaultSort: 'createdAt,desc' });
  const status = filters.status ?? '';
  const [searchInput, setSearchInput] = useState(filters.q ?? '');
  const debouncedSearch = useDebouncedValue(searchInput, 300);
  useEffect(() => { setFilter('q', debouncedSearch); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [debouncedSearch]);

  const policiesQuery = useQuery({
    queryKey: ['policies', page, size, sort, status, filters.q ?? ''],
    queryFn: () => validatedList('/api/v1/policies', PolicySummaryDtoSchema, {
      params: { page, size, sort, ...(status ? { status } : {}), ...(filters.q ? { q: filters.q } : {}) },
    }),
  });
  const policies = policiesQuery.data?.data ?? [];
  const total    = policiesQuery.data?.meta.total ?? 0;

  const columns: ColumnDef<PolicySummaryDto>[] = [
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
      accessorKey: 'totalSumInsured',
      header: 'Sum Insured',
      cell: ({ getValue }) => {
        const v = getValue() as number | null | undefined;
        return <span className="text-sm tabular-nums">{v == null ? '—' : `₦${v.toLocaleString()}`}</span>;
      },
    },
    {
      accessorKey: 'netPremium',
      header: 'Net Premium',
      cell: ({ getValue }) => {
        const v = getValue() as number | null | undefined;
        return <span className="text-sm font-medium tabular-nums">{v == null ? '—' : `₦${v.toLocaleString()}`}</span>;
      },
    },
    {
      // Computed column — DB enforces broker XOR agent (ck_policies_broker_xor_agent).
      // Falls back to "Direct" when both are null. No backing entity property, so
      // server sort is disabled (a plain label header, not the sort control).
      id:           'intermediary',
      enableSorting: false,
      accessorFn:   (row) => row.brokerName ?? row.agentName ?? 'Direct',
      header:       'Intermediary',
      cell: ({ row }) => {
        const { brokerName, agentName } = row.original;
        if (brokerName) {
          return (
            <div className="text-sm">
              <span className="text-muted-foreground">Broker · </span>
              <span className="font-medium">{brokerName}</span>
            </div>
          );
        }
        if (agentName) {
          return (
            <div className="text-sm">
              <span className="text-muted-foreground">Agent · </span>
              <span className="font-medium">{agentName}</span>
            </div>
          );
        }
        return <span className="text-sm text-muted-foreground">Direct</span>;
      },
    },
    {
      accessorKey: 'status',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Status" />,
      cell: ({ getValue }) => {
        const s = getValue() as PolicySummaryDto['status'];
        return <Badge variant={statusVariant[s]}>{s.toLowerCase().replace('_', ' ')}</Badge>;
      },
    },
    {
      accessorKey: 'naicomUid',
      header: 'NAICOM UID',
      cell: ({ getValue }) => <NaicomBadge uid={getValue() as string | undefined} />,
    },
    {
      accessorKey: 'policyEndDate',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Expiry" />,
      cell: ({ getValue }) => (
        <span className="text-sm text-muted-foreground">{getValue() as string}</span>
      ),
    },
    {
      id: 'actions',
      cell: ({ row }) => {
        const { status, id } = row.original;
        // Submit / Approve / Download all live on PolicyDetailPage — route there.
        // Add endorsement and Register claim route to their module landings,
        // which is where the create flows actually live.
        const goDetail = () => navigate(`/policies/${id}`);
        return (
          <DataTableRowActions
            row={row}
            actions={[
              { label: 'View details',         onClick: goDetail },
              ...(status === 'DRAFT'            ? [{ label: 'Submit for approval', onClick: goDetail }] : []),
              ...(status === 'ACTIVE'           ? [{ label: 'Add endorsement',     onClick: () => navigate('/endorsements') }] : []),
              ...(status === 'ACTIVE'           ? [{ label: 'Register claim',      onClick: () => navigate('/claims') }] : []),
              ...(status === 'PENDING_APPROVAL' ? [{ label: 'Approve policy',      onClick: goDetail }] : []),
              { label: 'Download document',    onClick: goDetail, separator: status !== 'DRAFT' },
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
          <div className="flex items-center gap-2">
            <Select value={status || 'ALL'} onValueChange={(v) => setFilter('status', v === 'ALL' ? '' : v)}>
              <SelectTrigger className="w-44"><SelectValue placeholder="Status" /></SelectTrigger>
              <SelectContent>
                <SelectItem value="ALL">All statuses</SelectItem>
                {POLICY_STATUSES.map((s) => (
                  <SelectItem key={s} value={s}>{s.toLowerCase().replace('_', ' ')}</SelectItem>
                ))}
              </SelectContent>
            </Select>
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
          </div>
        }
      />

      {policiesQuery.isLoading ? (
        <div className="space-y-3"><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /></div>
      ) : total === 0 && !status && !filters.q ? (
        <EmptyState
          title="No policies yet"
          description="Issue your first policy by converting an approved quote or creating one directly."
          action={<Button onClick={() => setCreateOpen(true)}>New Policy</Button>}
        />
      ) : (
        <DataTable
          columns={columns}
          data={policies}
          toolbar={{ searchPlaceholder: 'Search policies…', searchValue: searchInput, onSearchChange: setSearchInput }}
          serverPagination={{ page, size, total, onPageChange: setPage, onSizeChange: setSize, sort, onSortChange: setSort }}
        />
      )}

      <CreatePolicySheet open={createOpen} onOpenChange={setCreateOpen} onSuccess={() => setCreateOpen(false)} />
    </div>
  );
}
