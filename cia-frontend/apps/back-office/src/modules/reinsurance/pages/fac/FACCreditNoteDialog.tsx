import {
  Button, Separator,
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '@cia/ui';

interface FacOutward {
  id:           string;
  reference:    string;
  policyNumber: string;
  reinsurer:    string;
  sumInsured:   number;
  premiumRate:  number;
  offerDate:    string;
}

// Placeholder commission rate — will come from accepted FAC terms when backend is wired
const COMMISSION_RATE = 5;

interface Props {
  open:         boolean;
  onOpenChange: (v: boolean) => void;
  fac:          FacOutward | null;
}

function DetailRow({ label, value, highlight }: { label: string; value: string; highlight?: boolean }) {
  return (
    <div className="flex items-start gap-4 py-2" style={{ boxShadow: '0 1px 0 var(--border)' }}>
      <p className="w-44 shrink-0 text-sm text-muted-foreground">{label}</p>
      <p className={`text-sm font-medium ${highlight ? 'text-primary' : 'text-foreground'}`}>{value}</p>
    </div>
  );
}

export default function FACCreditNoteDialog({ open, onOpenChange, fac }: Props) {
  if (!fac) return null;

  const grossPremium = (fac.sumInsured * fac.premiumRate) / 100;
  const commission   = grossPremium * COMMISSION_RATE / 100;
  const netPremium   = grossPremium - commission;
  const cnRef        = `CN-FAC-${fac.reference.replace('FAC-OUT-', '')}`;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>FAC Credit Note</DialogTitle>
          <DialogDescription>
            Review the credit note details before submitting to Finance for payment by the reinsurer.
          </DialogDescription>
        </DialogHeader>

        <div className="rounded-lg border overflow-hidden">
          <div className="bg-muted/40 px-4 py-2 flex items-center justify-between">
            <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">Credit Note</p>
            <span className="font-mono text-xs text-primary">{cnRef}</span>
          </div>
          <div className="px-4 pb-2">
            <DetailRow label="FAC Reference"    value={fac.reference} />
            <DetailRow label="Policy"           value={fac.policyNumber} />
            <DetailRow label="Reinsurer"        value={fac.reinsurer} />
          </div>

          <Separator />
          <div className="px-4 pb-2">
            <DetailRow label="FAC Sum Insured"  value={`₦${fac.sumInsured.toLocaleString()}`} />
            <DetailRow label="FAC Premium Rate" value={`${fac.premiumRate}%`} />
            <DetailRow label="Gross FAC Premium" value={`₦${grossPremium.toLocaleString(undefined, { minimumFractionDigits: 2 })}`} />
            <DetailRow label={`Commission (${COMMISSION_RATE}%)`} value={`−₦${commission.toLocaleString(undefined, { minimumFractionDigits: 2 })}`} />
          </div>

          <div className="bg-muted/40 px-4 py-3 flex items-center justify-between">
            <p className="text-sm font-semibold">Net FAC Premium Due</p>
            <p className="text-base font-semibold text-primary">
              ₦{netPremium.toLocaleString(undefined, { minimumFractionDigits: 2 })}
            </p>
          </div>
        </div>

        <DialogFooter className="gap-2 sm:gap-2">
          <Button variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
          <Button variant="outline" onClick={() => {
            // TODO: GET /api/v1/reinsurance/fac/outward/{id}/credit-note/pdf
            onOpenChange(false);
          }}>
            Download PDF
          </Button>
          <Button onClick={() => {
            // TODO: POST /api/v1/reinsurance/fac/outward/{id}/credit-note
            onOpenChange(false);
          }}>
            Submit to Finance
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
