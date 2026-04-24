import {
  Badge, Button, Separator,
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '@cia/ui';
import type { DebitNoteDto } from '@cia/api-client';

// Mock policy details — replace with useGet(`/api/v1/policies/${policyNumber}`)
const MOCK_POLICY_DETAIL: Record<string, { product: string; class: string; startDate: string; endDate: string; premium: number }> = {
  'POL-2026-00001': { product: 'Private Motor Comprehensive',  class: 'Motor (Private)',  startDate: '2026-02-01', endDate: '2027-02-01', premium: 78_750 },
  'POL-2026-00002': { product: 'Fire & Burglary Standard',     class: 'Fire & Burglary', startDate: '2026-03-01', endDate: '2027-03-01', premium: 115_000 },
  'POL-2026-00003': { product: 'Private Motor Comprehensive',  class: 'Motor (Private)',  startDate: '2026-03-15', endDate: '2027-03-15', premium: 49_500 },
  'POL-2025-00088': { product: 'Marine Cargo Open Cover',      class: 'Marine Cargo',     startDate: '2025-03-01', endDate: '2026-03-01', premium: 40_000 },
  'POL-2026-00004': { product: 'Marine Cargo Open Cover',      class: 'Marine Cargo',     startDate: '2026-01-15', endDate: '2027-01-15', premium: 60_000 },
};

const DN_STATUS_VARIANT: Record<DebitNoteDto['status'], 'pending' | 'active' | 'draft'> = {
  OUTSTANDING:    'pending',
  PARTIALLY_PAID: 'draft',
  SETTLED:        'active',
};

interface Props {
  open:           boolean;
  onOpenChange:   (v: boolean) => void;
  debitNote:      DebitNoteDto | null;
  onPostReceipt:  (dn: DebitNoteDto) => void;
}

function DetailRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-start gap-4 py-2" style={{ boxShadow: '0 1px 0 var(--border)' }}>
      <p className="w-36 shrink-0 text-sm text-muted-foreground">{label}</p>
      <p className="text-sm font-medium text-foreground">{value}</p>
    </div>
  );
}

export default function DebitNoteDetailDialog({ open, onOpenChange, debitNote, onPostReceipt }: Props) {
  if (!debitNote) return null;

  const policy = MOCK_POLICY_DETAIL[debitNote.policyNumber];
  const canPost = debitNote.status === 'OUTSTANDING' || debitNote.status === 'PARTIALLY_PAID';

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <div className="flex items-center gap-2">
            <DialogTitle>{debitNote.number}</DialogTitle>
            <Badge variant={DN_STATUS_VARIANT[debitNote.status]} className="text-[10px]">
              {debitNote.status.toLowerCase().replace('_', ' ')}
            </Badge>
          </div>
          <DialogDescription>
            Review the policy and debit note details before posting a receipt.
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-0 rounded-lg border overflow-hidden">
          <div className="bg-muted/40 px-4 py-2">
            <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">Policy</p>
          </div>
          <div className="px-4 pb-2">
            <DetailRow label="Policy No."  value={debitNote.policyNumber} />
            <DetailRow label="Customer"    value={debitNote.customerName} />
            {policy && (
              <>
                <DetailRow label="Product"   value={policy.product} />
                <DetailRow label="Class"     value={policy.class} />
                <DetailRow label="Period"    value={`${policy.startDate} → ${policy.endDate}`} />
              </>
            )}
          </div>

          <Separator />

          <div className="bg-muted/40 px-4 py-2">
            <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">Debit Note</p>
          </div>
          <div className="px-4 pb-2">
            <DetailRow label="Debit Note"  value={debitNote.number} />
            <DetailRow label="Due Date"    value={debitNote.dueDate} />
          </div>
          <div className="bg-muted/40 px-4 py-3 flex items-center justify-between">
            <p className="text-sm font-semibold">Amount Due</p>
            <p className="text-base font-semibold text-primary">₦{debitNote.amount.toLocaleString()}</p>
          </div>
        </div>

        <DialogFooter className="gap-2 sm:gap-2">
          <Button variant="outline" onClick={() => onOpenChange(false)}>Close</Button>
          {canPost && (
            <Button onClick={() => { onOpenChange(false); onPostReceipt(debitNote); }}>
              Post Receipt
            </Button>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
