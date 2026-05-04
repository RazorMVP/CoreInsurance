import { useState } from 'react';
import {
  Badge, DataTable, DataTableColumnHeader, DataTableRowActions,
  PageSection, Separator,
} from '@cia/ui';
import { type ColumnDef } from '@tanstack/react-table';
import { useQuery } from '@tanstack/react-query';
import { apiClient, type CreditNoteDto, type FinanceEntityType, type PaymentDto } from '@cia/api-client';
import CreditNoteDetailDialog   from './CreditNoteDetailDialog';
import ProcessPaymentSheet      from './ProcessPaymentSheet';
import ReverseTransactionDialog, { type ReverseTarget } from '../ReverseTransactionDialog';

const ENTITY_LABELS: Record<FinanceEntityType, string> = {
  POLICY:        'Policy',
  ENDORSEMENT:   'Endorsement',
  CLAIM:         'Claim DV',
  CLAIM_EXPENSE: 'Claim Expense',
  COMMISSION:    'Commission',
  REINSURANCE:   'RI FAC',
};

const cnStatusVariant: Record<CreditNoteDto['status'], 'pending' | 'active' | 'draft' | 'rejected'> = {
  OUTSTANDING: 'pending',
  PARTIAL:     'draft',
  SETTLED:     'active',
  CANCELLED:   'rejected',
};

const payStatusVariant: Record<PaymentDto['status'], 'active' | 'pending' | 'rejected'> = {
  APPROVED: 'active',
  PENDING:  'pending',
  REVERSED: 'rejected',
  PAID:     'active',
};

export default function PayablesTab() {
  const creditNotesQuery = useQuery<CreditNoteDto[]>({
    queryKey: ['finance', 'credit-notes'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: CreditNoteDto[] }>('/api/v1/finance/credit-notes');
      return res.data.data;
    },
  });
  const creditNotes = creditNotesQuery.data ?? [];

  const paymentsQuery = useQuery<PaymentDto[]>({
    queryKey: ['finance', 'payments'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: PaymentDto[] }>('/api/v1/finance/payments');
      return res.data.data;
    },
  });
  const payments = paymentsQuery.data ?? [];

  // Credit note detail dialog
  const [cnDetail, setCnDetail] = useState<CreditNoteDto | null>(null);

  // Process payment sheet
  const [processPayTarget, setProcessPayTarget] = useState<CreditNoteDto | null>(null);

  // Reverse payment dialog
  const [reverseTarget, setReverseTarget] = useState<ReverseTarget | null>(null);

  function handleProcessPaymentFromDialog(cn: CreditNoteDto) {
    setCnDetail(null);
    setProcessPayTarget(cn);
  }

  const cnColumns: ColumnDef<CreditNoteDto>[] = [
    {
      accessorKey: 'creditNoteNumber',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Credit Note" />,
      cell: ({ row }) => (
        <button
          type="button"
          className="font-mono text-xs text-primary hover:underline underline-offset-2"
          onClick={() => setCnDetail(row.original)}
        >
          {row.original.creditNoteNumber}
        </button>
      ),
    },
    {
      accessorKey: 'entityType',
      header: 'Source',
      cell: ({ getValue }) => (
        <Badge variant="outline" className="text-xs">{ENTITY_LABELS[getValue() as FinanceEntityType]}</Badge>
      ),
    },
    {
      accessorKey: 'entityReference',
      header: 'Reference',
      cell: ({ getValue }) => <span className="font-mono text-xs text-muted-foreground">{getValue() as string}</span>,
    },
    {
      accessorKey: 'totalAmount',
      header: 'Amount',
      cell: ({ getValue }) => (
        <span className="text-sm font-medium tabular-nums">₦{(getValue() as number).toLocaleString()}</span>
      ),
    },
    {
      accessorKey: 'outstandingAmount',
      header: 'Outstanding',
      cell: ({ getValue }) => (
        <span className="text-sm font-medium tabular-nums text-amber-700">₦{(getValue() as number).toLocaleString()}</span>
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
            ...(row.original.status === 'OUTSTANDING' ? [{
              label: 'Process Payment',
              onClick: () => setCnDetail(row.original),
            }] : []),
            {
              label: 'View source',
              onClick: () => setCnDetail(row.original),
            },
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
        const cn = creditNotes.find(c => c.id === getValue());
        return <span className="font-mono text-xs text-muted-foreground">{cn?.creditNoteNumber ?? (getValue() as string)}</span>;
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
      cell: ({ row }) => {
        const linked = creditNotes.find(c => c.id === row.original.creditNoteId);
        return (
          <DataTableRowActions
            row={row}
            actions={[
              ...(row.original.status === 'PENDING' ? [
                { label: 'Approve payment', onClick: () => {} },
                { label: 'Reject',          onClick: () => {}, className: 'text-destructive' },
              ] : []),
              {
                label:     'Reverse',
                separator: row.original.status === 'APPROVED',
                className: 'text-destructive',
                onClick: () => setReverseTarget({
                  type:      'PAYMENT',
                  id:        row.original.id,
                  parentId:  row.original.creditNoteId,
                  reference: row.original.paymentNumber,
                  linkedRef: linked?.creditNoteNumber ?? row.original.creditNoteId,
                  amount:    row.original.amount,
                  method:    row.original.paymentMethod,
                  date:      row.original.createdAt,
                }),
              },
            ]}
          />
        );
      },
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
          data={creditNotes}
          toolbar={{ searchColumn: 'creditNoteNumber', searchPlaceholder: 'Search credit notes…' }}
        />
      </PageSection>

      <Separator />

      {/* Payments */}
      <PageSection title="Payments" description="Payments posted against credit notes.">
        <DataTable
          columns={payColumns}
          data={payments}
          toolbar={{ searchColumn: 'paymentNumber', searchPlaceholder: 'Search payments…' }}
        />
      </PageSection>

      {/* Credit note detail dialog */}
      <CreditNoteDetailDialog
        open={cnDetail !== null}
        onOpenChange={(v) => { if (!v) setCnDetail(null); }}
        creditNote={cnDetail}
        onProcessPayment={handleProcessPaymentFromDialog}
      />

      {/* Process payment sheet */}
      <ProcessPaymentSheet
        open={processPayTarget !== null}
        onOpenChange={(v) => { if (!v) setProcessPayTarget(null); }}
        creditNote={processPayTarget}
        onSuccess={() => setProcessPayTarget(null)}
      />

      {/* Reverse payment dialog */}
      <ReverseTransactionDialog
        open={reverseTarget !== null}
        onOpenChange={(v) => { if (!v) setReverseTarget(null); }}
        target={reverseTarget}
      />
    </div>
  );
}
