import { useState } from 'react';
import {
  Badge, Button, DataTable, DataTableColumnHeader, DataTableRowActions,
  PageSection,
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@cia/ui';
import { type ColumnDef } from '@tanstack/react-table';
import { useDownloadPaymentPdf, useEmailPayment, usePaymentList } from '../../hooks/usePayments';
import EmailConfirmDialog from '../EmailConfirmDialog';
import ReverseTransactionDialog, { type ReverseTarget } from '../ReverseTransactionDialog';
import type { FinanceEntityType, PaymentListItemResponse } from '@cia/api-client';

interface EmailTarget {
  cnId:           string;
  paymentId:      string;
  reference:      string;
  recipientEmail: string | null;
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

  const paymentsQuery = usePaymentList({ status, page, size: 20 });
  const payments = paymentsQuery.data?.data ?? [];
  const meta     = paymentsQuery.data?.meta;

  const downloadPdf      = useDownloadPaymentPdf();
  const emailPaymentMut  = useEmailPayment();

  const columns: ColumnDef<PaymentListItemResponse>[] = [
    {
      accessorKey: 'reference',
      header: ({ column }) => <DataTableColumnHeader column={column} title="Payment" />,
      cell: ({ getValue }) => <span className="font-mono text-xs">{getValue() as string}</span>,
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
        const r = row.original;
        const label = r.beneficiaryType
          ? (ENTITY_LABELS[r.beneficiaryType as FinanceEntityType] ?? r.beneficiaryType)
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
            <Badge variant={paymentStatusVariant[r.status]} className="text-[10px]">
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
              cnId:           r.creditNoteId,
              paymentId:      r.id,
              reference:      r.reference,
              recipientEmail: r.recipientEmail,
            }),
          });
        }
        if (r.pdfPath !== null) {
          actions.push({
            label: 'Download PDF',
            onClick: () => downloadPdf.mutate({
              cnId:      r.creditNoteId,
              paymentId: r.id,
              reference: r.reference,
            }),
          });
        }
        if (r.status === 'POSTED') {
          actions.push({
            label: 'Reverse',
            onClick: () => setReverseTarget({
              type:      'PAYMENT',
              id:        r.id,
              parentId:  r.creditNoteId,
              reference: r.reference,
              linkedRef: r.creditNoteNumber,
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

  return (
    <>
      <PageSection
        title="Payments"
        description="Flat list of all payments. Filter by status to surface reversals or recently-posted disbursements."
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
    </>
  );
}
