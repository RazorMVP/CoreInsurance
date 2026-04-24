import {
  Badge, Button, Separator,
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '@cia/ui';
import type { CreditNoteDto } from '@cia/api-client';

const SOURCE_LABELS: Record<CreditNoteDto['sourceType'], string> = {
  CLAIM:       'Claim DV',
  ENDORSEMENT: 'Endorsement',
  COMMISSION:  'Commission',
  REINSURANCE: 'RI FAC',
};

const CN_STATUS_VARIANT: Record<CreditNoteDto['status'], 'pending' | 'active'> = {
  OUTSTANDING: 'pending',
  PAID:        'active',
};

// Mock source details — replace with individual source API calls per type
const MOCK_SOURCE_DETAIL: Record<string, { ref: string; description: string; policyRef?: string; beneficiary?: string }> = {
  cl1:  { ref: 'CLM-2026-00001', description: 'Motor accident — third party bodily injury',  policyRef: 'POL-2026-00001', beneficiary: 'Chioma Okafor' },
  end1: { ref: 'END-2026-00001', description: 'Reduction in sum insured endorsement',         policyRef: 'POL-2026-00002', beneficiary: 'Alaba Trading Co.' },
  pol1: { ref: 'POL-2026-00001', description: 'Broker commission — Motor policy',             policyRef: 'POL-2026-00001', beneficiary: 'Chioma Okafor' },
  ri1:  { ref: 'FAC-OUT-2026-001', description: 'Net FAC premium — Munich Re motor cover',   beneficiary: 'Munich Re' },
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

  const source    = MOCK_SOURCE_DETAIL[creditNote.sourceId];
  const canProcess = creditNote.status === 'OUTSTANDING';

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <div className="flex items-center gap-2">
            <DialogTitle>{creditNote.number}</DialogTitle>
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
            <Badge variant="outline" className="text-[10px]">{SOURCE_LABELS[creditNote.sourceType]}</Badge>
          </div>
          <div className="px-4 pb-2">
            {source && (
              <>
                <DetailRow label="Reference"    value={source.ref} />
                <DetailRow label="Description"  value={source.description} />
                {source.policyRef  && <DetailRow label="Policy"      value={source.policyRef} />}
                {source.beneficiary && <DetailRow label="Beneficiary" value={source.beneficiary} />}
              </>
            )}
            {!source && (
              <DetailRow label="Source ID" value={creditNote.sourceId} />
            )}
          </div>

          <Separator />

          {/* Credit note section */}
          <div className="bg-muted/40 px-4 py-2">
            <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">Credit Note</p>
          </div>
          <div className="px-4 pb-2">
            <DetailRow label="Credit Note"  value={creditNote.number} />
            <DetailRow label="Date Raised"  value={creditNote.createdAt} />
          </div>
          <div className="bg-muted/40 px-4 py-3 flex items-center justify-between">
            <p className="text-sm font-semibold">Amount Payable</p>
            <p className="text-base font-semibold text-primary">₦{creditNote.amount.toLocaleString()}</p>
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
