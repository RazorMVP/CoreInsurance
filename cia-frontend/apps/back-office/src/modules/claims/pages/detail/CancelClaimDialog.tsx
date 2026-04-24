import { useState } from 'react';
import {
  Badge, Button,
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '@cia/ui';
import type { ClaimDto } from '@cia/api-client';

interface Props {
  open:         boolean;
  onOpenChange: (v: boolean) => void;
  claim:        ClaimDto | null;
  onConfirm:    (reason: string) => void;
}

function DetailRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-start gap-4 py-2" style={{ boxShadow: '0 1px 0 var(--border)' }}>
      <p className="w-36 shrink-0 text-sm text-muted-foreground">{label}</p>
      <p className="text-sm font-medium text-foreground">{value}</p>
    </div>
  );
}

const statusVariant: Record<string, 'active'|'pending'|'draft'|'rejected'|'cancelled'> = {
  REGISTERED: 'draft', PROCESSING: 'pending', PENDING_APPROVAL: 'pending',
  APPROVED: 'active', REJECTED: 'rejected', SETTLED: 'active',
};

export default function CancelClaimDialog({ open, onOpenChange, claim, onConfirm }: Props) {
  const [reason, setReason] = useState('');

  if (!claim) return null;

  return (
    <Dialog open={open} onOpenChange={(v) => { if (!v) { setReason(''); } onOpenChange(v); }}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Cancel Claim</DialogTitle>
          <DialogDescription>
            Cancelling a claim is permanent. Provide a reason before confirming.
          </DialogDescription>
        </DialogHeader>

        <div className="rounded-lg border overflow-hidden">
          <div className="bg-muted/40 px-4 py-2 flex items-center justify-between">
            <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
              {claim.claimNumber}
            </p>
            <Badge variant={statusVariant[claim.status] ?? 'draft'} className="text-[10px]">
              {claim.status.toLowerCase().replace('_', ' ')}
            </Badge>
          </div>
          <div className="px-4 pb-2">
            <DetailRow label="Policy"        value={claim.policyNumber} />
            <DetailRow label="Customer"      value={claim.customerName} />
            <DetailRow label="Incident Date" value={claim.incidentDate} />
          </div>
        </div>

        <div className="space-y-1.5">
          <label className="text-sm font-medium text-foreground">Reason for cancellation</label>
          <textarea
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            placeholder="e.g. Duplicate registration / Customer withdrew claim / No insurable interest confirmed"
            rows={3}
            className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm resize-none focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
          />
        </div>

        {/* Cannot be undone warning */}
        <div className="rounded-lg bg-[var(--status-rejected-bg)] px-4 py-3 space-y-1">
          <p className="text-xs font-semibold text-[var(--status-rejected-fg)]">This cannot be undone</p>
          <p className="text-xs text-[var(--status-rejected-fg)]/80">
            Cancelling this claim will permanently close the record. No payments can be made against a cancelled claim.
          </p>
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>Keep Claim</Button>
          <Button
            variant="destructive"
            disabled={reason.trim().length < 5}
            onClick={() => {
              // TODO: PATCH /api/v1/claims/{id}/cancel
              onConfirm(reason);
              setReason('');
              onOpenChange(false);
            }}
          >
            Cancel Claim
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
