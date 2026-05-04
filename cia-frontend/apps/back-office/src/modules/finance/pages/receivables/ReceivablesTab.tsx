import { useState } from 'react';
import {
  Badge, Button, DataTable, DataTableColumnHeader, DataTableRowActions,
  PageSection, Separator,
} from '@cia/ui';
import { type ColumnDef } from '@tanstack/react-table';
import { useQuery } from '@tanstack/react-query';
import { apiClient, type DebitNoteDto, type ReceiptDto } from '@cia/api-client';
import PostReceiptSheet         from './PostReceiptSheet';
import DebitNoteDetailDialog    from './DebitNoteDetailDialog';
import ReverseTransactionDialog, { type ReverseTarget } from '../ReverseTransactionDialog';

const dnStatusVariant: Record<DebitNoteDto['status'], 'pending' | 'active' | 'draft'> = {
  OUTSTANDING:    'pending',
  PARTIALLY_PAID: 'draft',
  SETTLED:        'active',
};

const rcStatusVariant: Record<ReceiptDto['status'], 'active' | 'pending' | 'rejected' | 'draft'> = {
  APPROVED:         'active',
  PENDING_APPROVAL: 'pending',
  DRAFT:            'draft',
  REVERSED:         'rejected',
};

export default function ReceivablesTab() {
  // PostReceiptSheet state
  const [sheetOpen,   setSheetOpen]   = useState(false);
  const [selectedDns, setSelectedDns] = useState<string[]>([]);
  const [bulkMode,    setBulkMode]    = useState(false);

  const debitNotesQuery = useQuery<DebitNoteDto[]>({
    queryKey: ['finance', 'debit-notes'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: DebitNoteDto[] }>('/api/v1/finance/debit-notes');
      return res.data.data;
    },
  });
  const debitNotes = debitNotesQuery.data ?? [];

  const receiptsQuery = useQuery<ReceiptDto[]>({
    queryKey: ['finance', 'receipts'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: ReceiptDto[] }>('/api/v1/finance/receipts');
      return res.data.data;
    },
  });
  const receipts = receiptsQuery.data ?? [];

  // Debit note detail dialog
  const [dnDetail, setDnDetail] = useState<DebitNoteDto | null>(null);

  // Reverse receipt dialog
  const [reverseTarget, setReverseTarget] = useState<ReverseTarget | null>(null);

  function openDetail(dn: DebitNoteDto) {
    setDnDetail(dn);
  }

  function handlePostReceiptFromDialog(dn: DebitNoteDto) {
    setDnDetail(null);
    setSelectedDns([dn.id]);
    setBulkMode(false);
    setSheetOpen(true);
  }

  const dnColumns: ColumnDef<DebitNoteDto>[] = [
    {
      accessorKey: 'number',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Debit Note" />,
      cell: ({ row }) => (
        <button
          type="button"
          className="font-mono text-xs text-primary hover:underline underline-offset-2"
          onClick={() => openDetail(row.original)}
        >
          {row.original.number}
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
        const s = getValue() as DebitNoteDto['status'];
        return <Badge variant={dnStatusVariant[s]} className="text-[10px]">{s.toLowerCase().replace('_', ' ')}</Badge>;
      },
    },
    {
      accessorKey: 'dueDate',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Due Date" />,
      cell: ({ getValue }) => <span className="text-sm text-muted-foreground">{getValue() as string}</span>,
    },
    {
      id: 'actions',
      cell: ({ row }) => (
        <DataTableRowActions
          row={row}
          actions={[
            ...(row.original.status === 'OUTSTANDING' ? [{
              label: 'Post Receipt',
              onClick: () => openDetail(row.original),
            }] : []),
            {
              label: 'View policy',
              onClick: () => openDetail(row.original),
            },
          ]}
        />
      ),
    },
  ];

  const rcColumns: ColumnDef<ReceiptDto>[] = [
    {
      accessorKey: 'receiptNumber',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Receipt No." />,
      cell: ({ getValue }) => <span className="font-mono text-xs text-primary">{getValue() as string}</span>,
    },
    {
      accessorKey: 'debitNoteNumber',
      header: 'Debit Note',
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
      accessorKey: 'paymentMethod',
      header: 'Method',
      cell: ({ getValue }) => <span className="text-sm text-muted-foreground">{getValue() as string}</span>,
    },
    {
      accessorKey: 'status',
      header: 'Status',
      cell: ({ getValue }) => {
        const s = getValue() as ReceiptDto['status'];
        return <Badge variant={rcStatusVariant[s]} className="text-[10px]">{s.toLowerCase().replace('_', ' ')}</Badge>;
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
            ...(row.original.status === 'PENDING_APPROVAL' ? [
              { label: 'Approve receipt', onClick: () => {} },
              { label: 'Reject',          onClick: () => {} },
            ] : []),
            {
              label:     'Reverse',
              separator: row.original.status === 'APPROVED',
              className: 'text-destructive',
              onClick: () => setReverseTarget({
                type:      'RECEIPT',
                id:        row.original.id,
                parentId:  row.original.debitNoteId,
                reference: row.original.receiptNumber,
                linkedRef: row.original.debitNoteNumber,
                amount:    row.original.amount,
                method:    row.original.paymentMethod,
                date:      row.original.createdAt,
              }),
            },
          ]}
        />
      ),
    },
  ];

  const outstanding = debitNotes.filter(d => d.status === 'OUTSTANDING');

  return (
    <div className="space-y-8">
      {/* Debit Notes */}
      <PageSection
        title="Outstanding Debit Notes"
        description="Post receipts against debit notes to record premium payments."
        actions={
          <div className="flex gap-2">
            <Button
              variant="outline"
              size="sm"
              onClick={() => { setBulkMode(true); setSelectedDns(outstanding.map(d => d.id)); setSheetOpen(true); }}
              disabled={outstanding.length === 0}
            >
              Bulk Receipt ({outstanding.length})
            </Button>
          </div>
        }
      >
        <DataTable
          columns={dnColumns}
          data={debitNotes}
          toolbar={{ searchColumn: 'customerName', searchPlaceholder: 'Search debit notes…' }}
        />
      </PageSection>

      <Separator />

      {/* Receipts */}
      <PageSection title="Receipts" description="Posted receipts awaiting or completed approval.">
        <DataTable
          columns={rcColumns}
          data={receipts}
          toolbar={{ searchColumn: 'receiptNumber', searchPlaceholder: 'Search receipts…' }}
        />
      </PageSection>

      {/* Post receipt sheet */}
      <PostReceiptSheet
        open={sheetOpen}
        onOpenChange={setSheetOpen}
        debitNoteIds={selectedDns}
        bulk={bulkMode}
        debitNotes={debitNotes}
        onSuccess={() => setSheetOpen(false)}
      />

      {/* Debit note detail dialog */}
      <DebitNoteDetailDialog
        open={dnDetail !== null}
        onOpenChange={(v) => { if (!v) setDnDetail(null); }}
        debitNote={dnDetail}
        onPostReceipt={handlePostReceiptFromDialog}
      />

      {/* Reverse receipt dialog */}
      <ReverseTransactionDialog
        open={reverseTarget !== null}
        onOpenChange={(v) => { if (!v) setReverseTarget(null); }}
        target={reverseTarget}
      />
    </div>
  );
}
