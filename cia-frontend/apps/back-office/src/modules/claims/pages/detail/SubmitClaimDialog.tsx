import {
  Badge, Button, Separator,
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '@cia/ui';
import type { ClaimDto } from '@cia/api-client';

interface Props {
  open:         boolean;
  onOpenChange: (v: boolean) => void;
  claim:        ClaimDto | null;
  onConfirm:    () => void;
}

function DetailRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-start gap-4 py-2" style={{ boxShadow: '0 1px 0 var(--border)' }}>
      <p className="w-36 shrink-0 text-sm text-muted-foreground">{label}</p>
      <p className="text-sm font-medium text-foreground">{value}</p>
    </div>
  );
}

export default function SubmitClaimDialog({ open, onOpenChange, claim, onConfirm }: Props) {
  if (!claim) return null;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Submit Claim for Approval</DialogTitle>
          <DialogDescription>
            Review the claim details before submitting. This action cannot be undone.
          </DialogDescription>
        </DialogHeader>

        <div className="rounded-lg border overflow-hidden">
          <div className="bg-muted/40 px-4 py-2 flex items-center justify-between">
            <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
              {claim.claimNumber}
            </p>
            <Badge variant="pending" className="text-[10px]">processing</Badge>
          </div>
          <div className="px-4 pb-2">
            <DetailRow label="Policy"        value={claim.policyNumber} />
            <DetailRow label="Customer"      value={claim.customerName} />
            <DetailRow label="Incident Date" value={claim.incidentDate} />
            <DetailRow label="Registered"    value={claim.registeredDate} />
          </div>
          <Separator />
          <div className="px-4 pb-2">
            <DetailRow label="Estimated Loss" value={`₦${claim.estimatedLoss.toLocaleString()}`} />
            <DetailRow label="Reserve"        value={`₦${claim.reserveAmount.toLocaleString()}`} />
          </div>
          {claim.description && (
            <>
              <Separator />
              <div className="px-4 py-3">
                <p className="text-xs text-muted-foreground mb-1">Description</p>
                <p className="text-sm text-foreground leading-relaxed line-clamp-3">{claim.description}</p>
              </div>
            </>
          )}
        </div>

        {/* Cannot be undone warning */}
        <div className="rounded-lg bg-[var(--status-pending-bg)] px-4 py-3 space-y-1">
          <p className="text-xs font-semibold text-[var(--status-pending-fg)]">This cannot be undone</p>
          <p className="text-xs text-[var(--status-pending-fg)]/80">
            Once submitted, the claim will be locked for editing. Reserves, expenses and comments cannot be changed until the claim is returned for revision.
          </p>
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
          <Button onClick={() => { onConfirm(); onOpenChange(false); }}>
            Submit for Approval
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
