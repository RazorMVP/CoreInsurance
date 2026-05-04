import {
  Badge, Button, Separator,
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '@cia/ui';
import type { CreditNoteDto, FinanceEntityType } from '@cia/api-client';

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
  if (!creditNote) return null;

  const canProcess = creditNote.status === 'OUTSTANDING' || creditNote.status === 'PARTIAL';

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
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
