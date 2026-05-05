import { useEffect, useState } from 'react';
import {
  Badge, Button,
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
  toast,
} from '@cia/ui';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { apiClient, type ApiError, type ApiResponse, type ClaimDto } from '@cia/api-client';

interface ApiHttpError { response?: { data?: ApiResponse<unknown> }; message?: string }

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

const statusVariant: Record<string, 'active' | 'pending' | 'draft' | 'rejected' | 'cancelled'> = {
  REGISTERED:          'draft',
  UNDER_INVESTIGATION: 'pending',
  RESERVED:            'pending',
  PENDING_APPROVAL:    'pending',
  APPROVED:            'active',
  REJECTED:            'rejected',
  SETTLED:             'active',
  WITHDRAWN:           'cancelled',
};

function showServerError(err: unknown, title: string) {
  const ax = err as ApiHttpError;
  const errors: ApiError[] = ax?.response?.data?.errors ?? [];
  const description = errors.length > 0
    ? errors.map(e => e.message).filter(Boolean).join('. ')
    : ax?.message ?? 'An unexpected error occurred. Please try again.';
  toast({ variant: 'destructive', title, description });
}

export default function CancelClaimDialog({ open, onOpenChange, claim, onConfirm }: Props) {
  const queryClient = useQueryClient();
  const [reason, setReason] = useState('');
  const [reasonErr, setReasonErr] = useState<string | null>(null);

  useEffect(() => {
    if (!open) { setReason(''); setReasonErr(null); }
  }, [open]);

  // Backend exposes claim cancellation as POST /api/v1/claims/{id}/withdraw
  // (the frontend audit's "/cancel" TODO was a naming difference). Body must
  // include a reason — see ClaimController.withdraw signature.
  const withdraw = useMutation({
    mutationFn: async ({ id, reason }: { id: string; reason: string }) => {
      await apiClient.post(`/api/v1/claims/${id}/withdraw`, { reason });
    },
    onSuccess: (_data, { reason: r }) => {
      queryClient.invalidateQueries({ queryKey: ['claims'] });
      if (claim) queryClient.invalidateQueries({ queryKey: ['claims', claim.id] });
      toast({ title: 'Claim cancelled', description: claim ? `${claim.claimNumber} withdrawn from processing.` : undefined });
      onConfirm(r);
      onOpenChange(false);
    },
    onError: (err) => showServerError(err, 'Could not cancel claim'),
  });

  if (!claim) return null;

  function handleConfirm() {
    if (reason.trim().length < 5) {
      setReasonErr('Reason must be at least 5 characters.');
      return;
    }
    if (!claim) return;
    withdraw.mutate({ id: claim.id, reason });
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
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
          <label className="text-sm font-medium text-foreground" htmlFor="cancel-claim-reason">Reason for cancellation</label>
          <textarea
            id="cancel-claim-reason"
            value={reason}
            onChange={(e) => { setReason(e.target.value); if (reasonErr) setReasonErr(null); }}
            placeholder="e.g. Duplicate registration / Customer withdrew claim / No insurable interest confirmed"
            rows={3}
            disabled={withdraw.isPending}
            className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm resize-none focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring disabled:opacity-60"
          />
          {reasonErr && <p className="text-xs text-destructive">{reasonErr}</p>}
        </div>

        {/* Cannot be undone warning */}
        <div className="rounded-lg bg-[var(--status-rejected-bg)] px-4 py-3 space-y-1">
          <p className="text-xs font-semibold text-[var(--status-rejected-fg)]">This cannot be undone</p>
          <p className="text-xs text-[var(--status-rejected-fg)]/80">
            Cancelling this claim will withdraw it from processing. No payments can be made against a withdrawn claim.
          </p>
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={withdraw.isPending}>Keep Claim</Button>
          <Button
            variant="destructive"
            disabled={withdraw.isPending || reason.trim().length < 5}
            onClick={handleConfirm}
          >
            {withdraw.isPending ? 'Cancelling…' : 'Cancel Claim'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
