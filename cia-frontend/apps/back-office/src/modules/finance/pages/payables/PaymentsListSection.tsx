import { useState } from 'react';
import {
  Badge, Button, Checkbox, DataTable, DataTableColumnHeader, DataTableRowActions,
  PageSection,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@cia/ui';
import { type ColumnDef } from '@tanstack/react-table';
import { useEmailPayment, usePaymentList, useSmsPayment } from '../../hooks/usePayments';
import DownloadIconButton from '../../components/DownloadIconButton';
import RecentDownloadsPanel from '../../components/RecentDownloadsPanel';
import BulkEmailSheet from '../BulkEmailSheet';
import BulkDownloadButton from '../BulkDownloadButton';
import EmailConfirmDialog from '../EmailConfirmDialog';
import SmsConfirmDialog from '../SmsConfirmDialog';
import ReverseTransactionDialog, { type ReverseTarget } from '../ReverseTransactionDialog';
import { formatPhone } from '../../lib/formatPhone';
import type { BulkDownloadItem, FinanceEntityType, PaymentListItemResponse } from '@cia/api-client';
import { formatNaira } from '@/lib/format';

interface EmailTarget {
  cnId:           string;
  paymentId:      string;
  reference:      string;
  recipientEmail: string | null;
}

interface SmsTarget {
  cnId:           string;
  paymentId:      string;
  reference:      string;
  recipientPhone: string | null;
}

const ENTITY_LABELS: Record<FinanceEntityType, string> = {
  POLICY:        'Policy',
  ENDORSEMENT:   'Endorsement',
  CLAIM:         'Claim DV',
  CLAIM_EXPENSE: 'Claim Expense',
  COMMISSION:    'Commission',
  REINSURANCE:   'RI FAC',
};

const paymentStatusVariant: Record<'POSTED' | 'REVERSED', 'active' | 'rejected'> = {
  POSTED:   'active',
  REVERSED: 'rejected',
};

export default function PaymentsListSection() {
  const [status,         setStatus]         = useState<'POSTED' | 'REVERSED' | undefined>(undefined);
  const [page,           setPage]           = useState(0);
  const [reverseTarget,  setReverseTarget]  = useState<ReverseTarget | null>(null);
  const [emailTarget,    setEmailTarget]    = useState<EmailTarget | null>(null);
  const [smsTarget,      setSmsTarget]      = useState<SmsTarget | null>(null);
  const [rowSelection,   setRowSelection]   = useState<Record<string, boolean>>({});
  const [bulkEmailOpen,  setBulkEmailOpen]  = useState(false);

  const paymentsQuery = usePaymentList({ status, page, size: 20 });
  const payments = paymentsQuery.data?.data ?? [];
  const meta     = paymentsQuery.data?.meta;

  const emailPaymentMut  = useEmailPayment();
  const smsPaymentMut    = useSmsPayment();

  const allPageIds   = payments.map((p) => p.id);
  const allSelected  = allPageIds.length > 0 && allPageIds.every((id) => rowSelection[id]);
  const someSelected = allPageIds.some((id) => rowSelection[id]);

  const columns: ColumnDef<PaymentListItemResponse>[] = [
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
      header: ({ column }) => <DataTableColumnHeader column={column} title="Payment" />,
      cell: ({ row }) => {
        const p = row.original;
        return (
          <div className="flex items-center gap-1">
            <span className="font-mono text-xs">{p.reference}</span>
            <DownloadIconButton
              type="PAYMENT"
              id={p.id}
              parentId={p.creditNoteId}
              reference={p.reference}
              pdfPath={p.pdfPath}
            />
          </div>
        );
      },
    },
    {
      accessorKey: 'creditNoteNumber',
      header: 'Credit Note',
      cell: ({ getValue }) => <span className="font-mono text-xs text-muted-foreground">{getValue() as string}</span>,
    },
    {
      accessorKey: 'beneficiaryType',
      header: 'Source',
      cell: ({ row }) => {
        const p = row.original;
        const label = p.beneficiaryType
          ? (ENTITY_LABELS[p.beneficiaryType as FinanceEntityType] ?? p.beneficiaryType)
          : '—';
        return <Badge variant="outline" className="text-xs">{label}</Badge>;
      },
    },
    {
      accessorKey: 'beneficiaryReference',
      header: 'Reference',
      cell: ({ getValue }) => (
        <span className="font-mono text-xs text-muted-foreground">{(getValue() as string) ?? '—'}</span>
      ),
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
        const p = row.original;
        return (
          <div className="flex flex-col gap-0.5">
            <Badge variant={paymentStatusVariant[p.status]} className="text-[10px]">
              {p.status.toLowerCase()}
            </Badge>
            {p.status === 'REVERSED' && p.reversedAt && (
              <span className="text-[11px] text-muted-foreground">
                Reversed {new Date(p.reversedAt).toLocaleString()} by {p.reversedBy ?? 'unknown'}
                {p.reversalReason ? ` — ${p.reversalReason}` : ''}
              </span>
            )}
            {p.emailSentAt && (
              <span className="text-[11px] text-muted-foreground">
                Last emailed {new Date(p.emailSentAt).toLocaleString()}
                {p.emailSentTo ? ` to ${p.emailSentTo}` : ''}
              </span>
            )}
            {p.smsSentAt && (
              <span className="text-[11px] text-muted-foreground">
                Last SMS&apos;d {new Date(p.smsSentAt).toLocaleString()}
                {p.smsSentTo ? ` to ${formatPhone(p.smsSentTo)}` : ''}
              </span>
            )}
          </div>
        );
      },
    },
    {
      id: 'actions',
      cell: ({ row }) => {
        const p = row.original;
        const actions: { label: string; onClick: () => void }[] = [];
        if (p.pdfPath !== null && p.recipientEmail !== null) {
          actions.push({
            label: 'Email PDF',
            onClick: () => setEmailTarget({
              cnId:           p.creditNoteId,
              paymentId:      p.id,
              reference:      p.reference,
              recipientEmail: p.recipientEmail,
            }),
          });
        }
        if (p.recipientPhone !== null) {
          actions.push({
            label: 'Send SMS',
            onClick: () => setSmsTarget({
              cnId:           p.creditNoteId,
              paymentId:      p.id,
              reference:      p.reference,
              recipientPhone: p.recipientPhone,
            }),
          });
        }
        if (p.status === 'POSTED') {
          actions.push({
            label: 'Reverse',
            onClick: () => setReverseTarget({
              type:      'PAYMENT',
              id:        p.id,
              parentId:  p.creditNoteId,
              reference: p.reference,
              linkedRef: p.creditNoteNumber,
              amount:    p.amount,
              method:    p.paymentMethod,
              date:      p.paymentDate ?? '',
            }),
          });
        }
        if (actions.length === 0) return null;
        return <DataTableRowActions row={row} actions={actions} />;
      },
    },
  ];

  const selectedRows = payments.filter((p) => rowSelection[p.id]);
  const selectedDownloadable: BulkDownloadItem[] = selectedRows
    .filter((p) => p.pdfPath !== null)
    .map((p) => ({ type: 'PAYMENT' as const, id: p.id }));
  const selectedEmailable = selectedRows.filter(
    (p) => p.pdfPath !== null && p.recipientEmail !== null,
  );

  return (
    <>
      <PageSection
        title="Payments"
        description="Flat list of all payments. Filter by status to surface reversals or recently-posted disbursements."
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
          data={payments}
          toolbar={{ searchColumn: 'beneficiaryReference', searchPlaceholder: 'Search payments…' }}
        />
        {meta && (
          <div className="mt-3 flex items-center justify-between text-sm text-muted-foreground">
            <span>Showing {payments.length} of {meta.total} payments</span>
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
                disabled={payments.length < 20}
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
        documentLabel={emailTarget ? `payment voucher ${emailTarget.reference}` : ''}
        isPending={emailPaymentMut.isPending}
        onConfirm={() => {
          if (!emailTarget) return;
          emailPaymentMut.mutate(
            {
              cnId:      emailTarget.cnId,
              paymentId: emailTarget.paymentId,
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
        documentLabel={smsTarget ? `payment voucher ${smsTarget.reference}` : ''}
        isPending={smsPaymentMut.isPending}
        onConfirm={() => {
          if (!smsTarget) return;
          smsPaymentMut.mutate(
            {
              cnId:      smsTarget.cnId,
              paymentId: smsTarget.paymentId,
              reference: smsTarget.reference,
            },
            { onSettled: () => setSmsTarget(null) },
          );
        }}
      />

      <BulkEmailSheet
        type="PAYMENT"
        rows={selectedEmailable.map((p) => ({
          id:        p.id,
          parentId:  p.creditNoteId,
          reference: p.reference,
        }))}
        open={bulkEmailOpen}
        onOpenChange={setBulkEmailOpen}
      />
    </>
  );
}
