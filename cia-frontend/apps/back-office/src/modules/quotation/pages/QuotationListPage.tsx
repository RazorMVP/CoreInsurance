import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Badge, Button, DataTable, DataTableColumnHeader, DataTableRowActions,
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger,
  EmptyState, PageHeader, Skeleton, toast,
} from '@cia/ui';
import { type ColumnDef } from '@tanstack/react-table';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient, type QuoteSummaryDto } from '@cia/api-client';
import { formatNaira } from '@/lib/format';
import SingleRiskQuoteSheet from './create/SingleRiskQuoteSheet';
import MultiRiskQuoteSheet  from './create/MultiRiskQuoteSheet';
import QuotePdfPreview, { type QuotePdfData } from './QuotePdfPreview';

// Extended mock — carries PDF data for approved quotes
// allow-mock: PDF preview shape — list endpoint doesn't include risk/clause detail
const mockQuotePdfData: Record<string, QuotePdfData> = {
  q1: {
    quoteNumber: 'QUO-2026-00001', issueDate: '2026-01-28',
    customerName: 'Chioma Okafor', productName: 'Private Motor Comprehensive', classOfBusiness: 'Motor (Private)',
    startDate: '2026-02-01', endDate: '2027-02-01',
    risks: [{
      description: '2022 Toyota Camry, Reg: LND-001-AA',
      sumInsured: 3_500_000, rate: 2.25,
      loadings:  [{ typeId: 'l1', typeName: 'High Risk Area',     format: 'PERCENT', value: 5 }],
      discounts: [{ typeId: 'd1', typeName: 'No Claims Discount', format: 'PERCENT', value: 2.5 }],
    }],
    quoteLoadings: [], quoteDiscounts: [], selectedClauseIds: ['c1', 'c2'],
    inputterName: 'Chidi Okafor', approverName: 'Adeola Bello',
    validityDays: 30,
  },
  q4: {
    quoteNumber: 'QUO-2026-00004', issueDate: '2026-01-10',
    customerName: 'Chioma Okafor', productName: 'Marine Cargo Open Cover', classOfBusiness: 'Marine Cargo',
    startDate: '2026-01-15', endDate: '2027-01-15',
    risks: [{
      description: 'General cargo — Lagos to Kano',
      sumInsured: 8_000_000, rate: 0.75, loadings: [], discounts: [],
    }],
    quoteLoadings: [], quoteDiscounts: [], selectedClauseIds: ['c7'],
    inputterName: 'Chidi Okafor', approverName: 'Adeola Bello',
    validityDays: 30,
  },
};

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
  const [pdfData,    setPdfData]    = useState<QuotePdfData | null>(null);

  const quotesQuery = useQuery<QuoteSummaryDto[]>({
    queryKey: ['quotes'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: QuoteSummaryDto[] }>('/api/v1/quotes');
      return res.data.data;
    },
  });
  const quotes = quotesQuery.data ?? [];

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
      id:         'intermediary',
      accessorFn: (row) => row.brokerName ?? row.agentName ?? 'Direct',
      header:     ({ column }) => <DataTableColumnHeader column={column} title="Intermediary" />,
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
              ...((status === 'APPROVED' || status === 'CONVERTED') && mockQuotePdfData[row.original.id]
                ? [{ label: 'Download PDF', onClick: (r: any) => setPdfData(mockQuotePdfData[r.original.id] ?? null) }]
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
      ) : quotes.length === 0 ? (
        <EmptyState
          title="No quotes yet"
          description="Create your first quote to start the underwriting process."
          action={<Button onClick={() => setSingleOpen(true)}>New Quote</Button>}
        />
      ) : (
        <DataTable
          columns={columns}
          data={quotes}
          toolbar={{ searchColumn: 'customerName', searchPlaceholder: 'Search by customer…' }}
        />
      )}

      <SingleRiskQuoteSheet open={singleOpen} onOpenChange={setSingleOpen} onSuccess={() => setSingleOpen(false)} />
      <MultiRiskQuoteSheet  open={multiOpen}  onOpenChange={setMultiOpen}  onSuccess={() => setMultiOpen(false)}  />
      <QuotePdfPreview open={!!pdfData} onOpenChange={(v) => { if (!v) setPdfData(null); }} data={pdfData} />
    </div>
  );
}
