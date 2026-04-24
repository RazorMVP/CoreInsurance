import {
  Badge, Button, Separator,
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '@cia/ui';

type OutwardStatus = 'OFFER_SENT' | 'ACCEPTED' | 'DECLINED' | 'DRAFT';

interface FacOutward {
  id:           string;
  reference:    string;
  policyNumber: string;
  reinsurer:    string;
  sumInsured:   number;
  premiumRate:  number;
  status:       OutwardStatus;
  offerDate:    string;
}

const STATUS_VARIANT: Record<OutwardStatus, 'active'|'pending'|'rejected'|'draft'> = {
  ACCEPTED: 'active', OFFER_SENT: 'pending', DECLINED: 'rejected', DRAFT: 'draft',
};
const STATUS_LABEL: Record<OutwardStatus, string> = {
  ACCEPTED: 'Accepted', OFFER_SENT: 'Offer sent', DECLINED: 'Declined', DRAFT: 'Draft',
};

interface Props {
  open:         boolean;
  onOpenChange: (v: boolean) => void;
  fac:          FacOutward | null;
}

function DetailRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-start gap-4 py-2" style={{ boxShadow: '0 1px 0 var(--border)' }}>
      <p className="w-40 shrink-0 text-sm text-muted-foreground">{label}</p>
      <p className="text-sm font-medium text-foreground">{value}</p>
    </div>
  );
}

export default function FACOfferSlipDialog({ open, onOpenChange, fac }: Props) {
  if (!fac) return null;

  const grossPremium = (fac.sumInsured * fac.premiumRate) / 100;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>FAC Offer Slip</DialogTitle>
          <DialogDescription>
            Review the offer slip sent to the reinsurer. Download a PDF copy for your records.
          </DialogDescription>
        </DialogHeader>

        <div className="rounded-lg border overflow-hidden">
          <div className="bg-muted/40 px-4 py-2 flex items-center justify-between">
            <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
              Offer Slip — {fac.reference}
            </p>
            <Badge variant={STATUS_VARIANT[fac.status]} className="text-[10px]">
              {STATUS_LABEL[fac.status]}
            </Badge>
          </div>
          <div className="px-4 pb-2">
            <DetailRow label="Policy"         value={fac.policyNumber} />
            <DetailRow label="Reinsurer"      value={fac.reinsurer} />
          </div>

          <Separator />
          <div className="px-4 pb-2">
            <DetailRow label="Sum Insured"    value={`₦${fac.sumInsured.toLocaleString()}`} />
            <DetailRow label="Premium Rate"   value={`${fac.premiumRate}%`} />
            <DetailRow label="Gross Premium"  value={`₦${grossPremium.toLocaleString(undefined, { minimumFractionDigits: 2 })}`} />
          </div>

          <Separator />
          <div className="px-4 pb-2">
            <DetailRow label="Offer Date"     value={fac.offerDate} />
          </div>
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>Close</Button>
          <Button onClick={() => {
            // TODO: GET /api/v1/reinsurance/fac/outward/{id}/offer-slip
            onOpenChange(false);
          }}>
            Download PDF
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
