import { useState } from 'react';
import {
  Badge, Button, DataTable, DataTableColumnHeader, DataTableRowActions,
  PageSection,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@cia/ui';
import { type ColumnDef } from '@tanstack/react-table';
import { useReceiptList } from '../../hooks/useReceipts';
import ReverseTransactionDialog, { type ReverseTarget } from '../ReverseTransactionDialog';
import type { ReceiptListItemResponse } from '@cia/api-client';

const receiptStatusVariant: Record<'POSTED' | 'REVERSED', 'active' | 'rejected'> = {
  POSTED:   'active',
  REVERSED: 'rejected',
};

export default function ReceiptsListSection() {
  const [status,         setStatus]         = useState<'POSTED' | 'REVERSED' | undefined>(undefined);
  const [page,           setPage]           = useState(0);
  const [reverseTarget,  setReverseTarget]  = useState<ReverseTarget | null>(null);

  const receiptsQuery = useReceiptList({ status, page, size: 20 });
  const receipts = receiptsQuery.data?.data ?? [];
  const meta     = receiptsQuery.data?.meta;

  const columns: ColumnDef<ReceiptListItemResponse>[] = [
    {
      accessorKey: 'reference',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Receipt" />,
      cell: ({ getValue }) => <span className="font-mono text-xs">{getValue() as string}</span>,
    },
    {
      accessorKey: 'debitNoteNumber',
      header: 'Debit Note',
      cell: ({ getValue }) => <span className="font-mono text-xs text-muted-foreground">{getValue() as string}</span>,
    },
    {
      accessorKey: 'customerName',
      header: 'Customer',
      cell: ({ getValue }) => <span className="text-sm">{(getValue() as string) ?? '—'}</span>,
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
      cell: ({ getValue }) => <span className="text-sm">{(getValue() as string).replace('_', ' ').toLowerCase()}</span>,
    },
    {
      accessorKey: 'paymentDate',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Date" />,
      cell: ({ getValue }) => <span className="text-sm text-muted-foreground">{(getValue() as string) ?? '—'}</span>,
    },
    {
      accessorKey: 'status',
      header: 'Status',
      cell: ({ row }) => {
        const r = row.original;
        return (
          <div className="flex flex-col gap-0.5">
            <Badge variant={receiptStatusVariant[r.status]} className="text-[10px]">
              {r.status.toLowerCase()}
            </Badge>
            {r.status === 'REVERSED' && r.reversedAt && (
              <span className="text-[11px] text-muted-foreground">
                Reversed {new Date(r.reversedAt).toLocaleString()} by {r.reversedBy ?? 'unknown'}
                {r.reversalReason ? ` — ${r.reversalReason}` : ''}
              </span>
            )}
          </div>
        );
      },
    },
    {
      id: 'actions',
      cell: ({ row }) => {
        const r = row.original;
        const actions = r.status === 'POSTED' ? [{
          label: 'Reverse',
          onClick: () => setReverseTarget({
            type:      'RECEIPT',
            id:        r.id,
            parentId:  r.debitNoteId,
            reference: r.reference,
            linkedRef: r.debitNoteNumber,
            amount:    r.amount,
            method:    r.paymentMethod,
            date:      r.paymentDate ?? '',
          }),
        }] : [];
        if (actions.length === 0) return null;
        return <DataTableRowActions row={row} actions={actions} />;
      },
    },
  ];

  return (
    <>
      <PageSection
        title="Receipts"
        description="Flat list of all receipts. Filter by status to surface reversals or recently-posted collections."
        actions={
          <Select
            value={status ?? 'ALL'}
            onValueChange={(v) => { setStatus(v === 'ALL' ? undefined : (v as 'POSTED' | 'REVERSED')); setPage(0); }}
          >
            <SelectTrigger className="w-40">
              <SelectValue placeholder="Status" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="ALL">All statuses</SelectItem>
              <SelectItem value="POSTED">Posted</SelectItem>
              <SelectItem value="REVERSED">Reversed</SelectItem>
            </SelectContent>
          </Select>
        }
      >
        <DataTable
          columns={columns}
          data={receipts}
          toolbar={{ searchColumn: 'customerName', searchPlaceholder: 'Search receipts…' }}
        />
        {meta && (
          <div className="mt-3 flex items-center justify-between text-sm text-muted-foreground">
            <span>Showing {receipts.length} of {meta.total} receipts</span>
            <div className="flex gap-2">
              <Button
                variant="outline"
                size="sm"
                disabled={page === 0}
                onClick={() => setPage((p) => Math.max(0, p - 1))}
              >
                Previous
              </Button>
              <Button
                variant="outline"
                size="sm"
                disabled={receipts.length < 20}
                onClick={() => setPage((p) => p + 1)}
              >
                Next
              </Button>
            </div>
          </div>
        )}
      </PageSection>

      <ReverseTransactionDialog
        open={reverseTarget !== null}
        onOpenChange={(v) => { if (!v) setReverseTarget(null); }}
        target={reverseTarget}
      />
    </>
  );
}
