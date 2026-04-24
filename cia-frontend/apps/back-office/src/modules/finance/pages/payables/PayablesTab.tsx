import {
  Badge, DataTable, DataTableColumnHeader, DataTableRowActions,
  PageSection, Separator,
} from '@cia/ui';
import { type ColumnDef } from '@tanstack/react-table';
import type { CreditNoteDto, PaymentDto } from '@cia/api-client';

// Credit notes — replace with useList('/api/v1/finance/credit-notes')
const mockCreditNotes: CreditNoteDto[] = [
  { id: 'cn1', number: 'CN-2026-00001', sourceType: 'CLAIM',        sourceId: 'cl1', amount: 450_000, status: 'OUTSTANDING', createdAt: '2026-03-15' },
  { id: 'cn2', number: 'CN-2026-00002', sourceType: 'ENDORSEMENT',  sourceId: 'end1',amount: 12_500,  status: 'PAID',        createdAt: '2026-02-20' },
  { id: 'cn3', number: 'CN-2026-00003', sourceType: 'COMMISSION',   sourceId: 'pol1',amount: 9_844,   status: 'OUTSTANDING', createdAt: '2026-02-01' },
  { id: 'cn4', number: 'CN-2026-00004', sourceType: 'REINSURANCE',  sourceId: 'ri1', amount: 28_000,  status: 'OUTSTANDING', createdAt: '2026-02-15' },
];

// Payments — replace with useList('/api/v1/finance/payments')
const mockPayments: PaymentDto[] = [
  { id: 'pay1', paymentNumber: 'PAY-2026-00001', creditNoteId: 'cn2', amount: 12_500, paymentMethod: 'Bank Transfer', status: 'APPROVED', createdAt: '2026-02-25' },
  { id: 'pay2', paymentNumber: 'PAY-2026-00002', creditNoteId: 'cn1', amount: 225_000, paymentMethod: 'Bank Transfer', status: 'PENDING',  createdAt: '2026-03-18' },
];

const sourceLabels: Record<CreditNoteDto['sourceType'], string> = {
  CLAIM:        'Claim DV',
  ENDORSEMENT:  'Endorsement',
  COMMISSION:   'Commission',
  REINSURANCE:  'RI FAC',
};

const cnStatusVariant: Record<CreditNoteDto['status'], 'pending' | 'active' | 'draft'> = {
  OUTSTANDING: 'pending',
  PAID:        'active',
};

const payStatusVariant: Record<PaymentDto['status'], 'active' | 'pending' | 'rejected'> = {
  APPROVED:  'active',
  PENDING:   'pending',
  REVERSED:  'rejected',
  PAID:      'active',
};

export default function PayablesTab() {
  const cnColumns: ColumnDef<CreditNoteDto>[] = [
    {
      accessorKey: 'number',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Credit Note" />,
      cell: ({ getValue }) => <span className="font-mono text-xs text-primary">{getValue() as string}</span>,
    },
    {
      accessorKey: 'sourceType',
      header: 'Source',
      cell: ({ getValue }) => (
        <Badge variant="outline" className="text-xs">{sourceLabels[getValue() as CreditNoteDto['sourceType']]}</Badge>
      ),
    },
    {
      accessorKey: 'sourceId',
      header: 'Reference',
      cell: ({ getValue }) => <span className="font-mono text-xs text-muted-foreground">{getValue() as string}</span>,
    },
    {
      accessorKey: 'amount',
      header: 'Amount',
      cell: ({ getValue }) => (
        <span className="text-sm font-medium tabular-nums">₦{(getValue() as number).toLocaleString()}</span>
      ),
    },
    {
      accessorKey: 'status',
      header: 'Status',
      cell: ({ getValue }) => {
        const s = getValue() as CreditNoteDto['status'];
        return <Badge variant={cnStatusVariant[s]} className="text-[10px]">{s.toLowerCase()}</Badge>;
      },
    },
    {
      accessorKey: 'createdAt',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Date" />,
      cell: ({ getValue }) => <span className="text-sm text-muted-foreground">{getValue() as string}</span>,
    },
    {
      id: 'actions',
      cell: ({ row }) => (
        <DataTableRowActions
          row={row}
          actions={[
            ...(row.original.status === 'OUTSTANDING' ? [{ label: 'Process Payment', onClick: () => {} }] : []),
            { label: 'View source', onClick: () => {} },
          ]}
        />
      ),
    },
  ];

  const payColumns: ColumnDef<PaymentDto>[] = [
    {
      accessorKey: 'paymentNumber',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Payment No." />,
      cell: ({ getValue }) => <span className="font-mono text-xs text-primary">{getValue() as string}</span>,
    },
    {
      accessorKey: 'creditNoteId',
      header: 'Credit Note',
      cell: ({ getValue }) => {
        const cn = mockCreditNotes.find(c => c.id === getValue());
        return <span className="font-mono text-xs text-muted-foreground">{cn?.number ?? getValue() as string}</span>;
      },
    },
    {
      accessorKey: 'amount',
      header: 'Amount',
      cell: ({ getValue }) => (
        <span className="text-sm font-medium tabular-nums">₦{(getValue() as number).toLocaleString()}</span>
      ),
    },
    {
      accessorKey: 'paymentMethod',
      header: 'Method',
      cell: ({ getValue }) => <span className="text-sm text-muted-foreground">{getValue() as string}</span>,
    },
    {
      accessorKey: 'status',
      header: 'Status',
      cell: ({ getValue }) => {
        const s = getValue() as PaymentDto['status'];
        return <Badge variant={payStatusVariant[s]} className="text-[10px]">{s.toLowerCase()}</Badge>;
      },
    },
    {
      id: 'actions',
      cell: ({ row }) => (
        <DataTableRowActions
          row={row}
          actions={[
            ...(row.original.status === 'PENDING' ? [{ label: 'Approve payment', onClick: () => {} }, { label: 'Reject', onClick: () => {}, className: 'text-destructive' }] : []),
            { label: 'Reverse', onClick: () => {}, separator: row.original.status === 'APPROVED', className: 'text-destructive' },
          ]}
        />
      ),
    },
  ];

  return (
    <div className="space-y-8">
      {/* Credit Notes */}
      <PageSection
        title="Outstanding Credit Notes"
        description="Credit notes awaiting payment — claims DVs, commissions, endorsement refunds and RI credits."
      >
        <DataTable
          columns={cnColumns}
          data={mockCreditNotes}
          toolbar={{ searchColumn: 'number', searchPlaceholder: 'Search credit notes…' }}
        />
      </PageSection>

      <Separator />

      {/* Payments */}
      <PageSection title="Payments" description="Payments posted against credit notes.">
        <DataTable
          columns={payColumns}
          data={mockPayments}
          toolbar={{ searchColumn: 'paymentNumber', searchPlaceholder: 'Search payments…' }}
        />
      </PageSection>
    </div>
  );
}
