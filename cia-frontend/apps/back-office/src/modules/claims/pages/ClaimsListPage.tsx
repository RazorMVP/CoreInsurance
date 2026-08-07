import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Badge, Button, DataTable, DataTableColumnHeader, DataTableRowActions,
  EmptyState, PageHeader,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  Skeleton, StatCard,
} from '@cia/ui';
import { type ColumnDef } from '@tanstack/react-table';
import { useQuery } from '@tanstack/react-query';
import { validatedGet, validatedList, ClaimDtoSchema, ClaimStatsDtoSchema, type ClaimDto } from '@cia/api-client';
import { formatNaira } from '@/lib/format';
import { useServerPagination } from '@/lib/use-server-pagination';
import { useDebouncedValue } from '@/lib/use-debounced-value';
import RegisterClaimSheet from './register/RegisterClaimSheet';
import SubmitClaimDialog  from './detail/SubmitClaimDialog';
import CancelClaimDialog  from './detail/CancelClaimDialog';

const statusVariant: Record<ClaimDto['status'], 'active' | 'pending' | 'draft' | 'rejected' | 'cancelled'> = {
  REGISTERED:          'draft',
  UNDER_INVESTIGATION: 'pending',
  RESERVED:            'pending',
  PENDING_APPROVAL:    'pending',
  APPROVED:            'active',
  REJECTED:            'rejected',
  SETTLED:             'active',
  WITHDRAWN:           'cancelled',
};
const CLAIM_STATUSES = Object.keys(statusVariant) as ClaimDto['status'][];

export default function ClaimsListPage() {
  const navigate = useNavigate();
  const [registerOpen,  setRegisterOpen]  = useState(false);
  const [submitTarget,  setSubmitTarget]  = useState<ClaimDto | null>(null);
  const [cancelTarget,  setCancelTarget]  = useState<ClaimDto | null>(null);

  const { page, size, sort, filters, setPage, setSize, setSort, setFilter } =
    useServerPagination({ defaultSize: 20, defaultSort: 'createdAt,desc' });
  const status = filters.status ?? '';
  const [searchInput, setSearchInput] = useState(filters.q ?? '');
  const debouncedSearch = useDebouncedValue(searchInput, 300);
  useEffect(() => { setFilter('q', debouncedSearch); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [debouncedSearch]);

  const claimsQuery = useQuery({
    queryKey: ['claims', page, size, sort, status, filters.q ?? ''],
    queryFn: () => validatedList('/api/v1/claims', ClaimDtoSchema, {
      params: { page, size, sort, ...(status ? { status } : {}), ...(filters.q ? { q: filters.q } : {}) },
    }),
  });
  const claims = claimsQuery.data?.data ?? [];
  const total  = claimsQuery.data?.meta.total ?? 0;

  // Dashboard stats are server-computed over ALL claims (not the current page).
  // "Approved" ≈ authorised-for-payment; the actual paid status lives in the
  // credit-note + payment chain in cia-finance.
  const statsQuery = useQuery({
    queryKey: ['claims', 'stats'],
    queryFn: () => validatedGet('/api/v1/claims/stats', ClaimStatsDtoSchema),
  });
  const stats = statsQuery.data;

  const columns: ColumnDef<ClaimDto>[] = [
    {
      accessorKey: 'claimNumber',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Claim No." />,
      cell: ({ row }) => (
        <button
          className="font-mono text-xs text-primary hover:underline"
          onClick={() => navigate(`/claims/${row.original.id}`)}
        >
          {row.original.claimNumber}
        </button>
      ),
    },
    {
      accessorKey: 'policyNumber',
      header: 'Policy',
      cell: ({ getValue }) => <span className="font-mono text-xs text-muted-foreground">{getValue() as string}</span>,
    },
    {
      accessorKey: 'customerName',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Customer" />,
      cell: ({ getValue }) => <span className="text-sm font-medium">{getValue() as string}</span>,
    },
    {
      accessorKey: 'description',
      header: 'Description',
      cell: ({ getValue }) => {
        const d = getValue() as string;
        return <span className="text-sm text-muted-foreground">{d.length > 45 ? d.slice(0, 45) + '…' : d}</span>;
      },
    },
    {
      accessorKey: 'reserveAmount',
      header: 'Reserve',
      cell: ({ getValue }) => (
        <span className="text-sm tabular-nums">{formatNaira(getValue() as number | null | undefined)}</span>
      ),
    },
    {
      accessorKey: 'approvedAmount',
      header: 'Approved',
      cell: ({ getValue }) => {
        const v = (getValue() as number | null | undefined) ?? 0;
        return <span className={`text-sm tabular-nums ${v > 0 ? 'font-medium text-primary' : 'text-muted-foreground'}`}>
          {v > 0 ? `₦${v.toLocaleString()}` : '—'}
        </span>;
      },
    },
    {
      accessorKey: 'status',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Status" />,
      cell: ({ getValue }) => {
        const s = getValue() as ClaimDto['status'];
        return <Badge variant={statusVariant[s]}>{s.toLowerCase().replace('_', ' ')}</Badge>;
      },
    },
    {
      accessorKey: 'incidentDate',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Incident" />,
      cell: ({ getValue }) => <span className="text-sm text-muted-foreground">{getValue() as string}</span>,
    },
    {
      id: 'actions',
      cell: ({ row }) => {
        const { status, id } = row.original;
        // Investigation / Approve / Reject / Generate DV all live on the
        // claim detail page — route there rather than duplicating workflows.
        const goDetail = () => navigate(`/claims/${id}`);
        return (
          <DataTableRowActions
            row={row}
            actions={[
              { label: 'View claim',          onClick: goDetail },
              ...(status === 'REGISTERED'                            ? [{ label: 'Start investigation', onClick: goDetail }] : []),
              ...(status === 'UNDER_INVESTIGATION' || status === 'RESERVED' ? [{ label: 'Submit for approval', onClick: () => setSubmitTarget(row.original) }] : []),
              ...(status === 'PENDING_APPROVAL'                      ? [{ label: 'Approve', onClick: goDetail }, { label: 'Reject', onClick: goDetail, className: 'text-destructive' }] : []),
              ...(status === 'APPROVED'                              ? [{ label: 'Generate DV', onClick: goDetail }] : []),
              ...(status !== 'SETTLED' && status !== 'WITHDRAWN' && status !== 'REJECTED'
                ? [{ label: 'Cancel claim', onClick: () => setCancelTarget(row.original), separator: true, className: 'text-destructive' }]
                : []),
            ]}
          />
        );
      },
    },
  ];

  return (
    <div className="p-6 space-y-5">
      <PageHeader
        title="Claims"
        description="Manage the full claims lifecycle from notification through settlement."
        actions={
          <div className="flex items-center gap-2">
            <Select value={status || 'ALL'} onValueChange={(v) => setFilter('status', v === 'ALL' ? '' : v)}>
              <SelectTrigger className="w-44"><SelectValue placeholder="Status" /></SelectTrigger>
              <SelectContent>
                <SelectItem value="ALL">All statuses</SelectItem>
                {CLAIM_STATUSES.map((s) => (
                  <SelectItem key={s} value={s}>{s.toLowerCase().replace('_', ' ')}</SelectItem>
                ))}
              </SelectContent>
            </Select>
            <Button variant="outline" onClick={() => navigate('/claims/bulk')}>Bulk Register</Button>
            <Button onClick={() => setRegisterOpen(true)}>Register Claim</Button>
          </div>
        }
      />

      {/* Dashboard summary — server-computed over all claims */}
      <div className="grid grid-cols-3 gap-4">
        <StatCard label="Open Claims"      value={String(stats?.openCount ?? 0)} sub={`${total} total`} />
        <StatCard label="Total Reserve"    value={`₦${(stats?.totalReserve ?? 0).toLocaleString()}`} sub="Outstanding reserve" />
        <StatCard label="Total Approved (YTD)" value={`₦${(stats?.totalApproved ?? 0).toLocaleString()}`} sub="Year to date" />
      </div>

      {claimsQuery.isLoading ? (
        <div className="space-y-3"><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /></div>
      ) : total === 0 && !status && !filters.q ? (
        <EmptyState
          title="No claims yet"
          description="Register a claim notification to start the claims process."
          action={<Button onClick={() => setRegisterOpen(true)}>Register Claim</Button>}
        />
      ) : (
        <DataTable
          columns={columns}
          data={claims}
          toolbar={{ searchPlaceholder: 'Search claims…', searchValue: searchInput, onSearchChange: setSearchInput }}
          serverPagination={{ page, size, total, onPageChange: setPage, onSizeChange: setSize, sort, onSortChange: setSort }}
        />
      )}

      <RegisterClaimSheet
        open={registerOpen}
        onOpenChange={setRegisterOpen}
        onSuccess={() => setRegisterOpen(false)}
      />

      <SubmitClaimDialog
        open={submitTarget !== null}
        onOpenChange={(v) => { if (!v) setSubmitTarget(null); }}
        claim={submitTarget}
        onConfirm={() => setSubmitTarget(null)}
      />

      <CancelClaimDialog
        open={cancelTarget !== null}
        onOpenChange={(v) => { if (!v) setCancelTarget(null); }}
        claim={cancelTarget}
        onConfirm={() => setCancelTarget(null)}
      />
    </div>
  );
}
