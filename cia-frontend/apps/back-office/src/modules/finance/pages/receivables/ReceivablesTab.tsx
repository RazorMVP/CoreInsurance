import { useState } from 'react';
import {
  Badge, Button, DataTable, DataTableColumnHeader, DataTableRowActions,
  PageSection,
  Tabs, TabsContent, TabsList, TabsTrigger,
} from '@cia/ui';
import { type ColumnDef } from '@tanstack/react-table';
import { useQuery } from '@tanstack/react-query';
import { z } from 'zod';
import {
  validatedGet, DebitNoteDtoSchema,
  type DebitNoteDto,
} from '@cia/api-client';
import PostReceiptSheet         from './PostReceiptSheet';
import DebitNoteDetailDialog    from './DebitNoteDetailDialog';
import ReceiptsListSection      from './ReceiptsListSection';

const dnStatusVariant: Record<DebitNoteDto['status'], 'pending' | 'active' | 'draft' | 'rejected'> = {
  OUTSTANDING: 'pending',
  PARTIAL:     'draft',
  SETTLED:     'active',
  CANCELLED:   'rejected',
  VOID:        'rejected',
};

export default function ReceivablesTab() {
  // PostReceiptSheet state
  const [sheetOpen,   setSheetOpen]   = useState(false);
  const [selectedDns, setSelectedDns] = useState<string[]>([]);
  const [bulkMode,    setBulkMode]    = useState(false);

  const debitNotesQuery = useQuery<DebitNoteDto[]>({
    queryKey: ['finance', 'debit-notes'],
    queryFn: () => validatedGet('/api/v1/debit-notes', z.array(DebitNoteDtoSchema)),
  });
  const debitNotes = debitNotesQuery.data ?? [];

  // Debit note detail dialog
  const [dnDetail, setDnDetail] = useState<DebitNoteDto | null>(null);

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
      accessorKey: 'debitNoteNumber',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Debit Note" />,
      cell: ({ row }) => (
        <button
          type="button"
          className="font-mono text-xs text-primary hover:underline underline-offset-2"
          onClick={() => openDetail(row.original)}
        >
          {row.original.debitNoteNumber}
        </button>
      ),
    },
    {
      accessorKey: 'entityReference',
      header: 'Reference',
      cell: ({ getValue }) => <span className="font-mono text-xs text-muted-foreground">{getValue() as string}</span>,
    },
    {
      accessorKey: 'customerName',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Customer" />,
      cell: ({ getValue }) => <span className="text-sm font-medium">{getValue() as string}</span>,
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
        const s = getValue() as DebitNoteDto['status'];
        return <Badge variant={dnStatusVariant[s]} className="text-[10px]">{s.toLowerCase()}</Badge>;
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
            ...(row.original.status === 'OUTSTANDING' || row.original.status === 'PARTIAL' ? [{
              label: 'Post Receipt',
              onClick: () => openDetail(row.original),
            }] : []),
            {
              label: 'View detail',
              onClick: () => openDetail(row.original),
            },
          ]}
        />
      ),
    },
  ];

  const outstanding = debitNotes.filter(d => d.status === 'OUTSTANDING' || d.status === 'PARTIAL');

  return (
    <Tabs defaultValue="debit-notes" className="space-y-4">
      <TabsList>
        <TabsTrigger value="debit-notes">Debit Notes</TabsTrigger>
        <TabsTrigger value="receipts">Receipts</TabsTrigger>
      </TabsList>

      <TabsContent value="debit-notes" className="space-y-8">
        {/* Debit Notes */}
        <PageSection
          title="Debit Notes"
          description="Premium receivables. Post receipts against a debit note to record collections."
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
      </TabsContent>

      <TabsContent value="receipts">
        <ReceiptsListSection />
      </TabsContent>
    </Tabs>
  );
}
