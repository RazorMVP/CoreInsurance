import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Badge, Button, DataTable, DataTableColumnHeader, DataTableRowActions,
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger,
  EmptyState, PageHeader,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
  Skeleton, toast,
} from '@cia/ui';
import { type ColumnDef } from '@tanstack/react-table';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient, validatedList, QuoteSummaryDtoSchema, type QuoteSummaryDto } from '@cia/api-client';
import { useServerPagination } from '@/lib/use-server-pagination';
import { useDebouncedValue } from '@/lib/use-debounced-value';
import { formatNaira } from '@/lib/format';
import SingleRiskQuoteSheet from './create/SingleRiskQuoteSheet';
import MultiRiskQuoteSheet  from './create/MultiRiskQuoteSheet';

const QUOTE_STATUSES = ['DRAFT', 'SUBMITTED', 'APPROVED', 'CONVERTED', 'REJECTED', 'EXPIRED'] as const;

const statusVariant: Record<QuoteSummaryDto['status'], 'active' | 'pending' | 'rejected' | 'draft' | 'cancelled'> = {
  APPROVED:  'active',
  SUBMITTED: 'pending',
  DRAFT:     'draft',
  CONVERTED: 'active',
  REJECTED:  'rejected',
  EXPIRED:   'cancelled',
};

export default function QuotationListPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [singleOpen, setSingleOpen] = useState(false);
  const [multiOpen,  setMultiOpen]  = useState(false);

  const { page, size, sort, filters, setPage, setSize, setSort, setFilter } =
    useServerPagination({ defaultSize: 20, defaultSort: 'createdAt,desc' });
  const status = filters.status ?? '';
  const [searchInput, setSearchInput] = useState(filters.q ?? '');
  const debouncedSearch = useDebouncedValue(searchInput, 300);
  useEffect(() => { setFilter('q', debouncedSearch); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [debouncedSearch]);

  const quotesQuery = useQuery({
    queryKey: ['quotes', page, size, sort, status, filters.q ?? ''],
    queryFn: () => validatedList('/api/v1/quotes', QuoteSummaryDtoSchema, {
      params: { page, size, sort, ...(status ? { status } : {}), ...(filters.q ? { q: filters.q } : {}) },
    }),
  });
  const quotes = quotesQuery.data?.data ?? [];
  const total  = quotesQuery.data?.meta.total ?? 0;

  // Duplicate — deep-copies the quote into a new DRAFT (F1c, Session 110).
  // Backend cascades risks + coinsurance participants + JSONB lists.
  const duplicate = useMutation({
    mutationFn: async (id: string) => {
      const res = await apiClient.post<{ data: { id: string } }>(
        `/api/v1/quotes/${id}/duplicate`,
      );
      return res.data.data;
    },
    onSuccess: (created) => {
      queryClient.invalidateQueries({ queryKey: ['quotes'] });
      toast({ title: 'Quote duplicated' });
      navigate(`/quotation/${created.id}`);
    },
    onError: () => {
      toast({ variant: 'destructive', title: 'Could not duplicate quote' });
    },
  });

  const columns: ColumnDef<QuoteSummaryDto>[] = [
    {
      accessorKey: 'quoteNumber',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Quote No." />,
      cell: ({ row }) => (
        <button
          className="font-mono text-xs text-primary hover:underline"
          onClick={() => navigate(`/quotation/${row.original.id}`)}
        >
          {row.original.quoteNumber}
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
      header: 'Product',
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
      cell: ({ getValue }) => (
        <span className="text-sm tabular-nums">{formatNaira(getValue() as number | null | undefined)}</span>
      ),
    },
    {
      accessorKey: 'netPremium',
      header: 'Net Premium',
      cell: ({ getValue }) => (
        <span className="text-sm font-medium tabular-nums">{formatNaira(getValue() as number | null | undefined)}</span>
      ),
    },
    {
      // Mirror of PolicyListPage's Intermediary column (B3 / Session 104).
      // ck_quotes_broker_xor_agent (V55) guarantees at most one of
      // brokerName / agentName is non-null — both null = Direct.
      id:            'intermediary',
      enableSorting: false,
      accessorFn:    (row) => row.brokerName ?? row.agentName ?? 'Direct',
      header:        'Intermediary',
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
        const s = getValue() as QuoteSummaryDto['status'];
        return <Badge variant={statusVariant[s]}>{s.toLowerCase()}</Badge>;
      },
    },
    {
      accessorKey: 'createdAt',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Created" />,
      cell: ({ getValue }) => (
        <span className="text-sm text-muted-foreground">{getValue() as string}</span>
      ),
    },
    {
      id: 'actions',
      cell: ({ row }) => {
        const { status } = row.original;
        return (
          <DataTableRowActions
            row={row}
            actions={[
              { label: 'View details', onClick: (r) => navigate(`/quotation/${r.original.id}`) },
              // Submit / Convert / Edit all live on the detail page — route there
              // rather than duplicating the workflow logic on the list row.
              ...(status === 'DRAFT'     ? [{ label: 'Submit for approval', onClick: (r: { original: QuoteSummaryDto }) => navigate(`/quotation/${r.original.id}`) }] : []),
              ...(status === 'APPROVED'  ? [{ label: 'Convert to policy',   onClick: (r: { original: QuoteSummaryDto }) => navigate(`/quotation/${r.original.id}`) }] : []),
              ...(status !== 'CONVERTED' ? [{ label: 'Edit quote',          onClick: (r: { original: QuoteSummaryDto }) => navigate(`/quotation/${r.original.id}`) }] : []),
              // The real per-quote PDF lives on the detail page (built from the
              // full quote); route there rather than mock-gating on seed ids.
              ...((status === 'APPROVED' || status === 'CONVERTED')
                ? [{ label: 'Download PDF', onClick: (r: { original: QuoteSummaryDto }) => navigate(`/quotation/${r.original.id}`) }]
                : []),
              { label: 'Duplicate', onClick: (r: { original: QuoteSummaryDto }) => duplicate.mutate(r.original.id) },
            ]}
          />
        );
      },
    },
  ];

  return (
    <div className="p-6 space-y-5">
      <PageHeader
        title="Quotation"
        description="Create and manage insurance quotes through the approval workflow."
        actions={
          <div className="flex gap-2">
            <Select value={status || 'ALL'} onValueChange={(v) => setFilter('status', v === 'ALL' ? '' : v)}>
              <SelectTrigger className="w-40"><SelectValue placeholder="Status" /></SelectTrigger>
              <SelectContent>
                <SelectItem value="ALL">All statuses</SelectItem>
                {QUOTE_STATUSES.map((s) => (
                  <SelectItem key={s} value={s}>{s.toLowerCase()}</SelectItem>
                ))}
              </SelectContent>
            </Select>
            <Button variant="outline" onClick={() => navigate('/quotation/bulk-upload')}>
              Bulk Upload
            </Button>
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button>New Quote ▾</Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end">
                <DropdownMenuItem onClick={() => setSingleOpen(true)}>
                  Single-risk quote
                </DropdownMenuItem>
                <DropdownMenuItem onClick={() => setMultiOpen(true)}>
                  Multi-risk quote
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </div>
        }
      />

      {quotesQuery.isLoading ? (
        <div className="space-y-3">
          <Skeleton className="h-10 w-full" />
          <Skeleton className="h-10 w-full" />
          <Skeleton className="h-10 w-full" />
        </div>
      ) : total === 0 && !status && !filters.q ? (
        <EmptyState
          title="No quotes yet"
          description="Create your first quote to start the underwriting process."
          action={<Button onClick={() => setSingleOpen(true)}>New Quote</Button>}
        />
      ) : (
        <DataTable
          columns={columns}
          data={quotes}
          toolbar={{ searchPlaceholder: 'Search quotes…', searchValue: searchInput, onSearchChange: setSearchInput }}
          serverPagination={{ page, size, total, onPageChange: setPage, onSizeChange: setSize, sort, onSortChange: setSort }}
        />
      )}

      <SingleRiskQuoteSheet open={singleOpen} onOpenChange={setSingleOpen} onSuccess={() => setSingleOpen(false)} />
      <MultiRiskQuoteSheet  open={multiOpen}  onOpenChange={setMultiOpen}  onSuccess={() => setMultiOpen(false)}  />
    </div>
  );
}
