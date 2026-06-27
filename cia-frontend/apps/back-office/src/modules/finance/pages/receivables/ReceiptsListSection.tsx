import { useState } from 'react';
import {
  Badge, Button, Checkbox, DataTable, DataTableColumnHeader, DataTableRowActions,
  PageSection,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@cia/ui';
import { type ColumnDef } from '@tanstack/react-table';
import { useEmailReceipt, useReceiptList, useSmsReceipt } from '../../hooks/useReceipts';
import DownloadIconButton from '../../components/DownloadIconButton';
import RecentDownloadsPanel from '../../components/RecentDownloadsPanel';
import BulkEmailSheet from '../BulkEmailSheet';
import BulkDownloadButton from '../BulkDownloadButton';
import EmailConfirmDialog from '../EmailConfirmDialog';
import SmsConfirmDialog from '../SmsConfirmDialog';
import ReverseTransactionDialog, { type ReverseTarget } from '../ReverseTransactionDialog';
import { formatPhone } from '../../lib/formatPhone';
import type { BulkDownloadItem, ReceiptListItemResponse } from '@cia/api-client';
import { formatNaira } from '@/lib/format';

interface EmailTarget {
  dnId:           string;
  receiptId:      string;
  reference:      string;
  recipientEmail: string | null;
}

interface SmsTarget {
  dnId:           string;
  receiptId:      string;
  reference:      string;
  recipientPhone: string | null;
}

const receiptStatusVariant: Record<'POSTED' | 'REVERSED', 'active' | 'rejected'> = {
  POSTED:   'active',
  REVERSED: 'rejected',
};

export default function ReceiptsListSection() {
  const [status,         setStatus]         = useState<'POSTED' | 'REVERSED' | undefined>(undefined);
  const [page,           setPage]           = useState(0);
  const [reverseTarget,  setReverseTarget]  = useState<ReverseTarget | null>(null);
  const [emailTarget,    setEmailTarget]    = useState<EmailTarget | null>(null);
  const [smsTarget,      setSmsTarget]      = useState<SmsTarget | null>(null);
  const [rowSelection,   setRowSelection]   = useState<Record<string, boolean>>({});
  const [bulkEmailOpen,  setBulkEmailOpen]  = useState(false);

  const receiptsQuery = useReceiptList({ status, page, size: 20 });
  const receipts = receiptsQuery.data?.data ?? [];
  const meta     = receiptsQuery.data?.meta;

  const emailReceiptMut = useEmailReceipt();
  const smsReceiptMut   = useSmsReceipt();

  const allPageIds   = receipts.map((r) => r.id);
  const allSelected  = allPageIds.length > 0 && allPageIds.every((id) => rowSelection[id]);
  const someSelected = allPageIds.some((id) => rowSelection[id]);

  const columns: ColumnDef<ReceiptListItemResponse>[] = [
    {
      id: 'select',
      header: () => (
        <Checkbox
          checked={allSelected ? true : someSelected ? 'indeterminate' : false}
          onCheckedChange={(checked) => {
            setRowSelection(() => {
              if (!checked) return {};
              const next: Record<string, boolean> = {};
              for (const id of allPageIds) next[id] = true;
              return next;
            });
          }}
          aria-label="Select all"
        />
      ),
      cell: ({ row }) => (
        <Checkbox
          checked={rowSelection[row.original.id] ?? false}
          onCheckedChange={(checked) => {
            setRowSelection((prev) => {
              const next = { ...prev };
              if (checked) next[row.original.id] = true;
              else delete next[row.original.id];
              return next;
            });
          }}
          aria-label="Select row"
        />
      ),
    },
    {
      accessorKey: 'reference',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Receipt" />,
      cell: ({ row }) => {
        const r = row.original;
        return (
          <div className="flex items-center gap-1">
            <span className="font-mono text-xs">{r.reference}</span>
            <DownloadIconButton
              type="RECEIPT"
              id={r.id}
              parentId={r.debitNoteId}
              reference={r.reference}
              pdfPath={r.pdfPath}
            />
          </div>
        );
      },
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
        <span className="text-sm font-medium tabular-nums">{formatNaira(getValue() as number | null | undefined)}</span>
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
            {r.emailSentAt && (
              <span className="text-[11px] text-muted-foreground">
                Last emailed {new Date(r.emailSentAt).toLocaleString()}
                {r.emailSentTo ? ` to ${r.emailSentTo}` : ''}
              </span>
            )}
            {r.smsSentAt && (
              <span className="text-[11px] text-muted-foreground">
                Last SMS&apos;d {new Date(r.smsSentAt).toLocaleString()}
                {r.smsSentTo ? ` to ${formatPhone(r.smsSentTo)}` : ''}
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
        const actions: { label: string; onClick: () => void }[] = [];
        if (r.pdfPath !== null && r.recipientEmail !== null) {
          actions.push({
            label: 'Email PDF',
            onClick: () => setEmailTarget({
              dnId:           r.debitNoteId,
              receiptId:      r.id,
              reference:      r.reference,
              recipientEmail: r.recipientEmail,
            }),
          });
        }
        if (r.recipientPhone !== null) {
          actions.push({
            label: 'Send SMS',
            onClick: () => setSmsTarget({
              dnId:           r.debitNoteId,
              receiptId:      r.id,
              reference:      r.reference,
              recipientPhone: r.recipientPhone,
            }),
          });
        }
        if (r.status === 'POSTED') {
          actions.push({
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
          });
        }
        if (actions.length === 0) return null;
        return <DataTableRowActions row={row} actions={actions} />;
      },
    },
  ];

  const selectedRows = receipts.filter((r) => rowSelection[r.id]);
  const selectedDownloadable: BulkDownloadItem[] = selectedRows
    .filter((r) => r.pdfPath !== null)
    .map((r) => ({ type: 'RECEIPT' as const, id: r.id }));
  const selectedEmailable = selectedRows.filter(
    (r) => r.pdfPath !== null && r.recipientEmail !== null,
  );

  return (
    <>
      <PageSection
        title="Receipts"
        description="Flat list of all receipts. Filter by status to surface reversals or recently-posted collections."
        actions={
          <div className="flex items-center gap-2">
            <RecentDownloadsPanel />
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
          </div>
        }
      >
        {selectedRows.length > 0 && (
          <div className="mb-2 flex items-center gap-2 rounded border bg-muted/40 p-2">
            <span className="text-sm text-muted-foreground">
              {selectedRows.length} selected
            </span>
            <Button
              size="sm"
              disabled={selectedEmailable.length === 0}
              onClick={() => setBulkEmailOpen(true)}
            >
              Email {selectedEmailable.length}
            </Button>
            <BulkDownloadButton items={selectedDownloadable} />
          </div>
        )}
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

      <EmailConfirmDialog
        open={emailTarget !== null}
        onOpenChange={(v) => { if (!v) setEmailTarget(null); }}
        recipientEmail={emailTarget?.recipientEmail ?? null}
        documentLabel={emailTarget ? `receipt ${emailTarget.reference}` : ''}
        isPending={emailReceiptMut.isPending}
        onConfirm={() => {
          if (!emailTarget) return;
          emailReceiptMut.mutate(
            {
              dnId:      emailTarget.dnId,
              receiptId: emailTarget.receiptId,
              reference: emailTarget.reference,
            },
            { onSettled: () => setEmailTarget(null) },
          );
        }}
      />

      <SmsConfirmDialog
        open={smsTarget !== null}
        onOpenChange={(v) => { if (!v) setSmsTarget(null); }}
        recipientPhone={smsTarget?.recipientPhone ?? null}
        documentLabel={smsTarget ? `receipt ${smsTarget.reference}` : ''}
        isPending={smsReceiptMut.isPending}
        onConfirm={() => {
          if (!smsTarget) return;
          smsReceiptMut.mutate(
            {
              dnId:      smsTarget.dnId,
              receiptId: smsTarget.receiptId,
              reference: smsTarget.reference,
            },
            { onSettled: () => setSmsTarget(null) },
          );
        }}
      />

      <BulkEmailSheet
        type="RECEIPT"
        rows={selectedEmailable.map((r) => ({
          id:        r.id,
          parentId:  r.debitNoteId,
          reference: r.reference,
        }))}
        open={bulkEmailOpen}
        onOpenChange={setBulkEmailOpen}
      />
    </>
  );
}
