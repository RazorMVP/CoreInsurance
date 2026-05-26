import { useState } from 'react';
import {
  Badge, Button, Separator,
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '@cia/ui';
import type { CreditNoteDto, FinanceEntityType } from '@cia/api-client';
import { useDownloadPaymentPdf, usePaymentList } from '../../hooks/usePayments';
import ReverseTransactionDialog, { type ReverseTarget } from '../ReverseTransactionDialog';

const ENTITY_LABELS: Record<FinanceEntityType, string> = {
  POLICY:        'Policy',
  ENDORSEMENT:   'Endorsement',
  CLAIM:         'Claim DV',
  CLAIM_EXPENSE: 'Claim Expense',
  COMMISSION:    'Commission',
  REINSURANCE:   'RI FAC',
};

const CN_STATUS_VARIANT: Record<CreditNoteDto['status'], 'pending' | 'active' | 'draft' | 'rejected'> = {
  OUTSTANDING: 'pending',
  PARTIAL:     'draft',
  SETTLED:     'active',
  CANCELLED:   'rejected',
};

interface Props {
  open:              boolean;
  onOpenChange:      (v: boolean) => void;
  creditNote:        CreditNoteDto | null;
  onProcessPayment:  (cn: CreditNoteDto) => void;
}

function DetailRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-start gap-4 py-2" style={{ boxShadow: '0 1px 0 var(--border)' }}>
      <p className="w-36 shrink-0 text-sm text-muted-foreground">{label}</p>
      <p className="text-sm font-medium text-foreground">{value}</p>
    </div>
  );
}

export default function CreditNoteDetailDialog({ open, onOpenChange, creditNote, onProcessPayment }: Props) {
  const [reverseTarget, setReverseTarget] = useState<ReverseTarget | null>(null);
  const paymentsQuery = usePaymentList(
    creditNote ? { creditNoteId: creditNote.id } : { creditNoteId: '' },
  );
  const payments = paymentsQuery.data?.data ?? [];

  const downloadPdf = useDownloadPaymentPdf();

  if (!creditNote) return null;

  const canProcess = creditNote.status === 'OUTSTANDING' || creditNote.status === 'PARTIAL';

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <div className="flex items-center gap-2">
            <DialogTitle>{creditNote.creditNoteNumber}</DialogTitle>
            <Badge variant={CN_STATUS_VARIANT[creditNote.status]} className="text-[10px]">
              {creditNote.status.toLowerCase()}
            </Badge>
          </div>
          <DialogDescription>
            Review the source details before processing a payment.
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-0 rounded-lg border overflow-hidden">
          {/* Source section */}
          <div className="bg-muted/40 px-4 py-2 flex items-center gap-2">
            <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">Source</p>
            <Badge variant="outline" className="text-[10px]">{ENTITY_LABELS[creditNote.entityType]}</Badge>
          </div>
          <div className="px-4 pb-2">
            <DetailRow label="Reference"   value={creditNote.entityReference} />
            {creditNote.description && (
              <DetailRow label="Description" value={creditNote.description} />
            )}
            {creditNote.beneficiaryName && (
              <DetailRow label="Beneficiary" value={creditNote.beneficiaryName} />
            )}
          </div>

          <Separator />

          {/* Credit note section */}
          <div className="bg-muted/40 px-4 py-2">
            <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">Credit Note</p>
          </div>
          <div className="px-4 pb-2">
            <DetailRow label="Credit Note"  value={creditNote.creditNoteNumber} />
            <DetailRow label="Due Date"     value={creditNote.dueDate} />
            <DetailRow label="Date Raised"  value={creditNote.createdAt} />
            <DetailRow label="Total"        value={`₦${creditNote.totalAmount.toLocaleString()}`} />
            <DetailRow label="Paid"         value={`₦${creditNote.paidAmount.toLocaleString()}`} />
          </div>
          <div className="bg-muted/40 px-4 py-3 flex items-center justify-between">
            <p className="text-sm font-semibold">Outstanding</p>
            <p className="text-base font-semibold text-primary">₦{creditNote.outstandingAmount.toLocaleString()}</p>
          </div>
        </div>

        {payments.length > 0 && (
          <section className="mt-2 rounded-lg border overflow-hidden">
            <div className="bg-muted/40 px-4 py-2 flex items-center justify-between">
              <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                Payments ({payments.length})
              </p>
            </div>
            <ul className="divide-y">
              {payments.map((p) => (
                <li key={p.id} className="flex items-start justify-between gap-3 px-4 py-2">
                  <div className="flex min-w-0 flex-col gap-0.5">
                    <span className="font-mono text-xs">{p.reference}</span>
                    <span className="text-xs text-muted-foreground">
                      ₦{p.amount.toLocaleString()} · {p.paymentMethod.replace('_', ' ').toLowerCase()} · {p.paymentDate ?? '—'}
                    </span>
                    {p.status === 'REVERSED' && p.reversedAt && (
                      <span className="text-[11px] text-muted-foreground">
                        Reversed {new Date(p.reversedAt).toLocaleString()} by {p.reversedBy ?? 'unknown'}
                        {p.reversalReason ? ` — ${p.reversalReason}` : ''}
                      </span>
                    )}
                  </div>
                  <div className="flex shrink-0 items-start gap-2">
                    <Badge
                      variant={p.status === 'POSTED' ? 'active' : 'rejected'}
                      className="text-[10px]"
                    >
                      {p.status.toLowerCase()}
                    </Badge>
                    {p.pdfPath && (
                      <Button
                        variant="outline"
                        size="sm"
                        onClick={() => downloadPdf.mutate({
                          cnId:      p.creditNoteId,
                          paymentId: p.id,
                          reference: p.reference,
                        })}
                        disabled={downloadPdf.isPending && downloadPdf.variables?.paymentId === p.id}
                      >
                        {downloadPdf.isPending && downloadPdf.variables?.paymentId === p.id ? 'Downloading…' : 'Download'}
                      </Button>
                    )}
                    {p.status === 'POSTED' && (
                      <Button
                        variant="outline"
                        size="sm"
                        onClick={() => setReverseTarget({
                          type:      'PAYMENT',
                          id:        p.id,
                          parentId:  p.creditNoteId,
                          reference: p.reference,
                          linkedRef: p.creditNoteNumber,
                          amount:    p.amount,
                          method:    p.paymentMethod,
                          date:      p.paymentDate ?? '',
                        })}
                      >
                        Reverse
                      </Button>
                    )}
                  </div>
                </li>
              ))}
            </ul>
          </section>
        )}

        <ReverseTransactionDialog
          open={reverseTarget !== null}
          onOpenChange={(v) => { if (!v) setReverseTarget(null); }}
          target={reverseTarget}
        />

        <DialogFooter className="gap-2 sm:gap-2">
          <Button variant="outline" onClick={() => onOpenChange(false)}>Close</Button>
          {canProcess && (
            <Button onClick={() => { onOpenChange(false); onProcessPayment(creditNote); }}>
              Process Payment
            </Button>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
