import {
  Button,
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '@cia/ui';

export interface ReverseTarget {
  type:       'RECEIPT' | 'PAYMENT';
  reference:  string;   // REC-xxx or PAY-xxx
  linkedRef:  string;   // debit note number or credit note number
  amount:     number;
  method:     string;
  date:       string;
}

interface Props {
  open:         boolean;
  onOpenChange: (v: boolean) => void;
  target:       ReverseTarget | null;
}

function DetailRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-start gap-4 py-2" style={{ boxShadow: '0 1px 0 var(--border)' }}>
      <p className="w-36 shrink-0 text-sm text-muted-foreground">{label}</p>
      <p className="text-sm font-medium text-foreground">{value}</p>
    </div>
  );
}

export default function ReverseTransactionDialog({ open, onOpenChange, target }: Props) {
  if (!target) return null;

  const isReceipt = target.type === 'RECEIPT';
  const title     = isReceipt ? 'Reverse Receipt' : 'Reverse Payment';
  const linkedLabel = isReceipt ? 'Debit Note' : 'Credit Note';

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-sm">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription>
            Review the details below before confirming. This action cannot be undone.
          </DialogDescription>
        </DialogHeader>

        <div className="rounded-lg border overflow-hidden">
          <div className="bg-muted/40 px-4 py-2">
            <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
              {isReceipt ? 'Receipt' : 'Payment'} Details
            </p>
          </div>
          <div className="px-4 pb-2">
            <DetailRow label={isReceipt ? 'Receipt No.' : 'Payment No.'} value={target.reference} />
            <DetailRow label={linkedLabel}  value={target.linkedRef} />
            <DetailRow label="Method"       value={target.method} />
            <DetailRow label="Date"         value={target.date} />
          </div>
          <div className="bg-muted/40 px-4 py-3 flex items-center justify-between">
            <p className="text-sm font-semibold">Amount</p>
            <p className="text-base font-semibold">₦{target.amount.toLocaleString()}</p>
          </div>
        </div>

        {/* Cannot be undone warning */}
        <div className="rounded-lg bg-[var(--status-rejected-bg)] px-4 py-3">
          <p className="text-xs font-semibold text-[var(--status-rejected-fg)] mb-1">This cannot be undone</p>
          <p className="text-xs text-[var(--status-rejected-fg)]/80">
            {isReceipt
              ? 'Reversing this receipt will mark the debit note as Outstanding again. The customer will need to make a new payment.'
              : 'Reversing this payment will mark the credit note as Outstanding again. A new payment will need to be processed.'}
          </p>
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
          <Button variant="destructive" onClick={() => {
            // TODO: POST /api/v1/finance/${isReceipt ? 'receipts' : 'payments'}/${id}/reverse
            onOpenChange(false);
          }}>
            Confirm Reversal
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
