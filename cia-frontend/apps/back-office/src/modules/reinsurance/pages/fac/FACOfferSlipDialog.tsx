import {
  Badge, Button, Separator,
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '@cia/ui';
import type { FacCoverDto, FacCoverStatus } from '@cia/api-client';

const STATUS_VARIANT: Record<FacCoverStatus, 'active' | 'pending' | 'rejected'> = {
  PENDING:   'pending',
  CONFIRMED: 'active',
  CANCELLED: 'rejected',
};

const STATUS_LABEL: Record<FacCoverStatus, string> = {
  PENDING:   'Pending',
  CONFIRMED: 'Confirmed',
  CANCELLED: 'Cancelled',
};

interface Props {
  open:         boolean;
  onOpenChange: (v: boolean) => void;
  fac:          FacCoverDto | null;
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
              Offer Slip — {fac.facReference}
            </p>
            <Badge variant={STATUS_VARIANT[fac.status]} className="text-[10px]">
              {STATUS_LABEL[fac.status]}
            </Badge>
          </div>
          <div className="px-4 pb-2">
            <DetailRow label="Policy"     value={fac.policyNumber} />
            <DetailRow label="Reinsurer"  value={fac.reinsuranceCompanyName} />
          </div>

          <Separator />
          <div className="px-4 pb-2">
            <DetailRow label="Sum Insured Ceded" value={`₦${fac.sumInsuredCeded.toLocaleString()}`} />
            <DetailRow label="Premium Rate"      value={`${fac.premiumRate}%`} />
            <DetailRow label="Gross Premium"     value={`₦${fac.premiumCeded.toLocaleString(undefined, { minimumFractionDigits: 2 })}`} />
          </div>

          <Separator />
          <div className="px-4 pb-2">
            <DetailRow label="Cover Period" value={`${fac.coverFrom} → ${fac.coverTo}`} />
            {fac.offerSlipReference && <DetailRow label="Offer Ref" value={fac.offerSlipReference} />}
          </div>
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>Close</Button>
          <Button onClick={() => {
            // TODO (backend gap): GET /api/v1/ri/fac-covers/{id}/offer-slip
            onOpenChange(false);
          }}>
            Download PDF
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
