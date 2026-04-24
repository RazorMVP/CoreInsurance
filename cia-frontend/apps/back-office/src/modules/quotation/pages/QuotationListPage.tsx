import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Badge, Button, DataTable, DataTableColumnHeader, DataTableRowActions,
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger,
  EmptyState, PageHeader,
} from '@cia/ui';
import { type ColumnDef } from '@tanstack/react-table';
import type { QuoteDto } from '@cia/api-client';
import SingleRiskQuoteSheet from './create/SingleRiskQuoteSheet';
import MultiRiskQuoteSheet  from './create/MultiRiskQuoteSheet';

const mockQuotes: QuoteDto[] = [
  { id: 'q1', quoteNumber: 'QUO-2026-00001', customerId: 'c1', customerName: 'Chioma Okafor',     productId: 'p1', productName: 'Private Motor Comprehensive', classOfBusinessId: '1', classOfBusinessName: 'Motor (Private)',  businessType: 'DIRECT', status: 'APPROVED',  sumInsured: 3_500_000, premium: 78_750,  discount: 0,     netPremium: 78_750,  startDate: '2026-02-01', endDate: '2027-02-01', version: 1, createdAt: '2026-01-28', updatedAt: '2026-01-30' },
  { id: 'q2', quoteNumber: 'QUO-2026-00002', customerId: 'c2', customerName: 'Alaba Trading Co.', productId: 'p3', productName: 'Fire & Burglary Standard',       classOfBusinessId: '3', classOfBusinessName: 'Fire & Burglary', businessType: 'DIRECT', status: 'SUBMITTED', sumInsured: 15_000_000, premium: 120_000, discount: 5_000, netPremium: 115_000, startDate: '2026-03-01', endDate: '2027-03-01', version: 2, createdAt: '2026-02-01', updatedAt: '2026-02-05' },
  { id: 'q3', quoteNumber: 'QUO-2026-00003', customerId: 'c3', customerName: 'Emeka Eze',         productId: 'p1', productName: 'Private Motor Comprehensive', classOfBusinessId: '1', classOfBusinessName: 'Motor (Private)',  businessType: 'DIRECT', status: 'DRAFT',     sumInsured: 2_200_000, premium: 49_500,  discount: 0,     netPremium: 49_500,  startDate: '2026-03-15', endDate: '2027-03-15', version: 1, createdAt: '2026-02-10', updatedAt: '2026-02-10' },
  { id: 'q4', quoteNumber: 'QUO-2026-00004', customerId: 'c1', customerName: 'Chioma Okafor',     productId: 'p4', productName: 'Marine Cargo Open Cover',       classOfBusinessId: '4', classOfBusinessName: 'Marine Cargo',    businessType: 'DIRECT', status: 'CONVERTED', sumInsured: 8_000_000, premium: 60_000,  discount: 0,     netPremium: 60_000,  startDate: '2026-01-15', endDate: '2027-01-15', version: 1, createdAt: '2026-01-10', updatedAt: '2026-01-15' },
  { id: 'q5', quoteNumber: 'QUO-2026-00005', customerId: 'c5', customerName: 'Ngozi Adeyemi',     productId: 'p1', productName: 'Private Motor Comprehensive', classOfBusinessId: '1', classOfBusinessName: 'Motor (Private)',  businessType: 'DIRECT', status: 'REJECTED',  sumInsured: 4_000_000, premium: 90_000,  discount: 0,     netPremium: 90_000,  startDate: '2026-02-20', endDate: '2027-02-20', version: 3, createdAt: '2026-02-08', updatedAt: '2026-02-12' },
];

const statusVariant: Record<QuoteDto['status'], 'active' | 'pending' | 'rejected' | 'draft' | 'cancelled'> = {
  APPROVED:  'active',
  SUBMITTED: 'pending',
  DRAFT:     'draft',
  CONVERTED: 'active',
  REJECTED:  'rejected',
  EXPIRED:   'cancelled',
};

export default function QuotationListPage() {
  const navigate = useNavigate();
  const [singleOpen, setSingleOpen] = useState(false);
  const [multiOpen,  setMultiOpen]  = useState(false);

  const columns: ColumnDef<QuoteDto>[] = [
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
        const s = getValue() as QuoteDto['status'];
        return <Badge variant={statusVariant[s]}>{s.toLowerCase()}</Badge>;
      },
    },
    {
      accessorKey: 'version',
      header: 'Ver.',
      cell: ({ getValue }) => (
        <span className="text-xs text-muted-foreground">v{getValue() as number}</span>
      ),
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
              ...(status === 'DRAFT'     ? [{ label: 'Submit for approval', onClick: () => {} }] : []),
              ...(status === 'APPROVED'  ? [{ label: 'Convert to policy',   onClick: () => {} }] : []),
              ...(status !== 'CONVERTED' ? [{ label: 'Edit quote',          onClick: () => {} }] : []),
              { label: 'Duplicate', onClick: () => {} },
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

      {mockQuotes.length === 0 ? (
        <EmptyState
          title="No quotes yet"
          description="Create your first quote to start the underwriting process."
          action={<Button onClick={() => setSingleOpen(true)}>New Quote</Button>}
        />
      ) : (
        <DataTable
          columns={columns}
          data={mockQuotes}
          toolbar={{ searchColumn: 'customerName', searchPlaceholder: 'Search by customer…' }}
        />
      )}

      <SingleRiskQuoteSheet open={singleOpen} onOpenChange={setSingleOpen} onSuccess={() => setSingleOpen(false)} />
      <MultiRiskQuoteSheet  open={multiOpen}  onOpenChange={setMultiOpen}  onSuccess={() => setMultiOpen(false)}  />
    </div>
  );
}
