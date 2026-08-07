import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Badge, Button, DataTable, DataTableColumnHeader, DataTableRowActions,
  EmptyState, PageHeader,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  Skeleton,
} from '@cia/ui';
import { type ColumnDef } from '@tanstack/react-table';
import { useQuery } from '@tanstack/react-query';
import { validatedList, EndorsementDtoSchema, ENDORSEMENT_TYPE_LABELS, type EndorsementDto } from '@cia/api-client';
import { useServerPagination } from '@/lib/use-server-pagination';
import { useDebouncedValue } from '@/lib/use-debounced-value';
import { formatNaira } from '@/lib/format';
import CreateEndorsementSheet from './create/CreateEndorsementSheet';

const statusVariant: Record<EndorsementDto['status'], 'active' | 'pending' | 'draft' | 'rejected'> = {
  APPROVED:  'active',
  SUBMITTED: 'pending',
  DRAFT:     'draft',
  REJECTED:  'rejected',
};

const ENDORSEMENT_STATUSES = ['DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED'] as const;
const ENDORSEMENT_TYPES = Object.keys(ENDORSEMENT_TYPE_LABELS) as EndorsementDto['endorsementType'][];

export default function EndorsementsListPage() {
  const navigate = useNavigate();
  const [createOpen, setCreateOpen] = useState(false);

  const { page, size, sort, filters, setPage, setSize, setSort, setFilter } =
    useServerPagination({ defaultSize: 20, defaultSort: 'createdAt,desc' });
  const status = filters.status ?? '';
  const type   = filters.endorsementType ?? '';
  const [searchInput, setSearchInput] = useState(filters.q ?? '');
  const debouncedSearch = useDebouncedValue(searchInput, 300);
  useEffect(() => { setFilter('q', debouncedSearch); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [debouncedSearch]);

  const endorsementsQuery = useQuery({
    queryKey: ['endorsements', page, size, sort, status, type, filters.q ?? ''],
    queryFn: () => validatedList('/api/v1/endorsements', EndorsementDtoSchema, {
      params: {
        page, size, sort,
        ...(status ? { status } : {}),
        ...(type ? { endorsementType: type } : {}),
        ...(filters.q ? { q: filters.q } : {}),
      },
    }),
  });
  const endorsements = endorsementsQuery.data?.data ?? [];
  const total        = endorsementsQuery.data?.meta.total ?? 0;

  const columns: ColumnDef<EndorsementDto>[] = [
    {
      accessorKey: 'endorsementNumber',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Endorsement No." />,
      cell: ({ row }) => (
        <button
          className="font-mono text-xs text-primary hover:underline"
          onClick={() => navigate(`/endorsements/${row.original.id}`)}
        >
          {row.original.endorsementNumber}
        </button>
      ),
    },
    {
      accessorKey: 'policyNumber',
      header: 'Policy',
      cell: ({ getValue }) => <span className="font-mono text-xs text-muted-foreground">{getValue() as string}</span>,
    },
    {
      accessorKey: 'endorsementType',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Type" />,
      cell: ({ getValue }) => (
        <Badge variant="outline" className="text-xs whitespace-nowrap">
          {ENDORSEMENT_TYPE_LABELS[getValue() as EndorsementDto['endorsementType']]}
        </Badge>
      ),
    },
    {
      accessorKey: 'newSumInsured',
      header: 'New Sum Insured',
      cell: ({ getValue }) => (
        <span className="text-sm tabular-nums">{formatNaira(getValue() as number | null | undefined)}</span>
      ),
    },
    {
      accessorKey: 'premiumAdjustment',
      header: 'Pro-rata Premium',
      cell: ({ getValue }) => {
        const v = getValue() as number | null | undefined;
        if (v == null) return <span className="text-sm tabular-nums">—</span>;
        return (
          <span className={`text-sm font-medium tabular-nums ${v < 0 ? 'text-destructive' : ''}`}>
            {v < 0 ? '−' : ''}₦{Math.abs(v).toLocaleString()}
          </span>
        );
      },
    },
    {
      accessorKey: 'status',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Status" />,
      cell: ({ getValue }) => {
        const s = getValue() as EndorsementDto['status'];
        return <Badge variant={statusVariant[s]}>{s.toLowerCase()}</Badge>;
      },
    },
    {
      accessorKey: 'effectiveDate',
      header: 'Effective Date',
      cell: ({ getValue }) => <span className="text-sm text-muted-foreground">{getValue() as string}</span>,
    },
    {
      id: 'actions',
      cell: ({ row }) => {
        const { status, id } = row.original;
        // All workflow actions live on the detail page — list rows route there.
        const goDetail = () => navigate(`/endorsements/${id}`);
        return (
          <DataTableRowActions
            row={row}
            actions={[
              { label: 'View details',      onClick: goDetail },
              ...(status === 'DRAFT'     ? [{ label: 'Submit for approval', onClick: goDetail }] : []),
              ...(status === 'SUBMITTED' ? [{ label: 'Approve',             onClick: goDetail }, { label: 'Reject', onClick: goDetail, className: 'text-destructive' }] : []),
              ...(status === 'APPROVED'  ? [{ label: 'Download document',   onClick: goDetail }] : []),
            ]}
          />
        );
      },
    },
  ];

  return (
    <div className="p-6 space-y-5">
      <PageHeader
        title="Endorsements"
        description="Manage policy amendments — renewals, extensions, cancellations and sum insured changes."
        actions={
          <div className="flex gap-2">
            <Select value={status || 'ALL'} onValueChange={(v) => setFilter('status', v === 'ALL' ? '' : v)}>
              <SelectTrigger className="w-36"><SelectValue placeholder="Status" /></SelectTrigger>
              <SelectContent>
                <SelectItem value="ALL">All statuses</SelectItem>
                {ENDORSEMENT_STATUSES.map((s) => (
                  <SelectItem key={s} value={s}>{s.toLowerCase()}</SelectItem>
                ))}
              </SelectContent>
            </Select>
            <Select value={type || 'ALL'} onValueChange={(v) => setFilter('endorsementType', v === 'ALL' ? '' : v)}>
              <SelectTrigger className="w-48"><SelectValue placeholder="Type" /></SelectTrigger>
              <SelectContent>
                <SelectItem value="ALL">All types</SelectItem>
                {ENDORSEMENT_TYPES.map((t) => (
                  <SelectItem key={t} value={t}>{ENDORSEMENT_TYPE_LABELS[t]}</SelectItem>
                ))}
              </SelectContent>
            </Select>
            <Button
              variant="outline"
              onClick={() => navigate('/endorsements/reports/debit-note-analysis')}
            >
              Debit Note Analysis
            </Button>
            <Button onClick={() => setCreateOpen(true)}>New Endorsement</Button>
          </div>
        }
      />

      {endorsementsQuery.isLoading ? (
        <div className="space-y-3"><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /></div>
      ) : total === 0 && !status && !type && !filters.q ? (
        <EmptyState
          title="No endorsements yet"
          description="Create an endorsement to amend an existing policy."
          action={<Button onClick={() => setCreateOpen(true)}>New Endorsement</Button>}
        />
      ) : (
        <DataTable
          columns={columns}
          data={endorsements}
          toolbar={{ searchPlaceholder: 'Search endorsements…', searchValue: searchInput, onSearchChange: setSearchInput }}
          serverPagination={{ page, size, total, onPageChange: setPage, onSizeChange: setSize, sort, onSortChange: setSort }}
        />
      )}

      <CreateEndorsementSheet
        open={createOpen}
        onOpenChange={setCreateOpen}
        onSuccess={() => setCreateOpen(false)}
      />
    </div>
  );
}
