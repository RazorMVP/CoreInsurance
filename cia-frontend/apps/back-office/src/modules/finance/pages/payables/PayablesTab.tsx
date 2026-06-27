import { useState } from 'react';
import {
  Badge, DataTable, DataTableColumnHeader, DataTableRowActions,
  PageSection,
  Tabs, TabsContent, TabsList, TabsTrigger,
} from '@cia/ui';
import { type ColumnDef } from '@tanstack/react-table';
import { useQuery } from '@tanstack/react-query';
import { z } from 'zod';
import {
  validatedGet, CreditNoteDtoSchema,
  type CreditNoteDto, type FinanceEntityType,
} from '@cia/api-client';
import { formatNaira } from '@/lib/format';
import CreditNoteDetailDialog   from './CreditNoteDetailDialog';
import ProcessPaymentSheet      from './ProcessPaymentSheet';
import PaymentsListSection      from './PaymentsListSection';

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

export default function PayablesTab() {
  const creditNotesQuery = useQuery<CreditNoteDto[]>({
    queryKey: ['finance', 'credit-notes'],
    queryFn: () => validatedGet('/api/v1/credit-notes', z.array(CreditNoteDtoSchema)),
  });
  const creditNotes = creditNotesQuery.data ?? [];

  // Credit note detail dialog
  const [cnDetail, setCnDetail] = useState<CreditNoteDto | null>(null);

  // Process payment sheet
  const [processPayTarget, setProcessPayTarget] = useState<CreditNoteDto | null>(null);

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
        <span className="text-sm font-medium tabular-nums">{formatNaira(getValue() as number | null | undefined)}</span>
      ),
    },
    {
      accessorKey: 'outstandingAmount',
      header: 'Outstanding',
      cell: ({ getValue }) => (
        <span className="text-sm font-medium tabular-nums text-amber-700">{formatNaira(getValue() as number | null | undefined)}</span>
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
            ...(row.original.status === 'OUTSTANDING' || row.original.status === 'PARTIAL' ? [{
              label: 'Process Payment',
              onClick: () => setCnDetail(row.original),
            }] : []),
            {
              label: 'View detail',
              onClick: () => setCnDetail(row.original),
            },
          ]}
        />
      ),
    },
  ];

  return (
    <Tabs defaultValue="credit-notes" className="space-y-4">
      <TabsList>
        <TabsTrigger value="credit-notes">Credit Notes</TabsTrigger>
        <TabsTrigger value="payments">Payments</TabsTrigger>
      </TabsList>

      <TabsContent value="credit-notes" className="space-y-8">
        {/* Credit Notes */}
        <PageSection
          title="Credit Notes"
          description="Payables — claims DVs, commissions, endorsement refunds and RI credits. Process a payment against a credit note to settle."
        >
          <DataTable
            columns={cnColumns}
            data={creditNotes}
            toolbar={{ searchColumn: 'creditNoteNumber', searchPlaceholder: 'Search credit notes…' }}
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
      </TabsContent>

      <TabsContent value="payments">
        <PaymentsListSection />
      </TabsContent>
    </Tabs>
  );
}
