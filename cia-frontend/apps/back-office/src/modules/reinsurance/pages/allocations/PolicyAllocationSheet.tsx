import {
  Badge, Button, Card, CardContent, CardHeader, CardTitle,
  Separator, Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle,
  toast,
} from '@cia/ui';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import {
  apiClient,
  type ApiError, type ApiResponse,
  type AllocationDto, type AllocationStatus,
} from '@cia/api-client';

interface ApiHttpError { response?: { data?: ApiResponse<unknown> }; message?: string }

// Display status: backend statuses + a derived EXCESS_CAPACITY when
// allocation.excessAmount > 0. Composed by the parent.
type DisplayStatus = AllocationStatus | 'EXCESS_CAPACITY';

interface Props {
  open:                   boolean;
  onOpenChange:           (v: boolean) => void;
  allocation:             AllocationDto | null;
  /** Derived display status — EXCESS_CAPACITY when excessAmount > 0. */
  displayStatus:          DisplayStatus;
  /** Looked-up class-of-business name (parent has the lookup map). */
  classOfBusinessName:    string;
  /** Looked-up treaty display name (parent has the lookup). */
  treatyDisplayName:      string;
  /** Looked-up treaty year, when available. */
  treatyYear:             number | null;
  /** Per-line reinsurer summary string for display. */
  reinsurersDisplay:      string;
  /** Open the Create FAC sheet (only used when EXCESS_CAPACITY). */
  onCreateFAC?:           () => void;
}

const STATUS_VARIANT: Record<DisplayStatus, 'active' | 'pending' | 'draft' | 'rejected'> = {
  DRAFT:           'draft',
  CONFIRMED:       'active',
  CANCELLED:       'rejected',
  EXCESS_CAPACITY: 'rejected',
};

const STATUS_LABEL: Record<DisplayStatus, string> = {
  DRAFT:           'Auto-allocated',
  CONFIRMED:       'Confirmed',
  CANCELLED:       'Cancelled',
  EXCESS_CAPACITY: 'Excess Capacity',
};

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-start gap-4 py-2" style={{ boxShadow: '0 1px 0 var(--border)' }}>
      <p className="w-36 shrink-0 text-sm text-muted-foreground">{label}</p>
      <p className="text-sm font-medium text-foreground">{value}</p>
    </div>
  );
}

function showServerError(err: unknown, title: string) {
  const ax = err as ApiHttpError;
  const errors: ApiError[] = ax?.response?.data?.errors ?? [];
  const description = errors.length > 0
    ? errors.map(e => e.message).filter(Boolean).join('. ')
    : ax?.message ?? 'An unexpected error occurred. Please try again.';
  toast({ variant: 'destructive', title, description });
}

export default function PolicyAllocationSheet({
  open, onOpenChange, allocation, displayStatus,
  classOfBusinessName, treatyDisplayName, treatyYear, reinsurersDisplay,
  onCreateFAC,
}: Props) {
  const queryClient = useQueryClient();

  const confirm = useMutation({
    mutationFn: async (id: string) => {
      await apiClient.post(`/api/v1/ri/allocations/${id}/confirm`);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['ri', 'allocations'] });
      toast({ title: 'Allocation confirmed' });
      onOpenChange(false);
    },
    onError: (err) => showServerError(err, 'Could not confirm allocation'),
  });

  const cancel = useMutation({
    mutationFn: async (id: string) => {
      await apiClient.post(`/api/v1/ri/allocations/${id}/cancel`);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['ri', 'allocations'] });
      toast({ title: 'Allocation cancelled' });
      onOpenChange(false);
    },
    onError: (err) => showServerError(err, 'Could not cancel allocation'),
  });

  if (!allocation) return null;

  const sumInsured = allocation.ourShareSumInsured;
  const retention  = allocation.retainedAmount;
  const ceding     = allocation.cededAmount;
  const retentionPct = sumInsured > 0 ? Math.round(retention / sumInsured * 100) : 0;
  const cedingPct    = sumInsured > 0 ? Math.round(ceding    / sumInsured * 100) : 0;

  const canConfirm = displayStatus === 'DRAFT';
  const canCancel  = displayStatus === 'DRAFT' || displayStatus === 'CONFIRMED';

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="w-full sm:max-w-lg overflow-y-auto">
        <SheetHeader>
          <div className="flex items-center gap-2">
            <SheetTitle>{allocation.policyNumber}</SheetTitle>
            <Badge variant={STATUS_VARIANT[displayStatus]} className="text-[10px]">
              {STATUS_LABEL[displayStatus]}
            </Badge>
          </div>
          <SheetDescription>
            RI allocation · {treatyDisplayName}
          </SheetDescription>
        </SheetHeader>

        <div className="mt-6 space-y-4">
          {/* Policy + allocation details */}
          <Card>
            <CardHeader><CardTitle>Policy &amp; Allocation</CardTitle></CardHeader>
            <CardContent>
              <Row label="Allocation No." value={allocation.allocationNumber} />
              <Row label="Policy"         value={allocation.policyNumber} />
              <Row label="Class"          value={classOfBusinessName} />
              <Row label="Sum Insured"    value={`₦${sumInsured.toLocaleString()}`} />
              <Row label="Premium"        value={`₦${allocation.ourSharePremium.toLocaleString()}`} />
            </CardContent>
          </Card>

          {/* RI allocation */}
          <Card>
            <CardHeader><CardTitle>Reinsurance Allocation</CardTitle></CardHeader>
            <CardContent>
              <Row label="Treaty"     value={treatyDisplayName} />
              <Row label="Reinsurers" value={reinsurersDisplay} />
              {treatyYear !== null && <Row label="Year" value={String(treatyYear)} />}
              <Separator className="my-3" />
              <div className="space-y-3">
                {/* Visual split bar */}
                <div>
                  <div className="flex justify-between text-xs text-muted-foreground mb-1.5">
                    <span>Our Retention ({retentionPct}%)</span>
                    <span>Ceded ({cedingPct}%)</span>
                  </div>
                  <div className="flex h-3 w-full overflow-hidden rounded-full bg-muted">
                    <div
                      className="h-full bg-charcoal-400 rounded-l-full"
                      style={{ width: `${retentionPct}%` }}
                    />
                    <div className="h-full bg-primary rounded-r-full flex-1" />
                  </div>
                </div>
                <Row label="Retention" value={`₦${retention.toLocaleString()} (${retentionPct}%)`} />
                <Row label="Ceding"    value={`₦${ceding.toLocaleString()} (${cedingPct}%)`} />
                {allocation.excessAmount > 0 && (
                  <Row label="Excess" value={`₦${allocation.excessAmount.toLocaleString()}`} />
                )}
              </div>

              {displayStatus === 'EXCESS_CAPACITY' && (
                <div className="mt-3 rounded-lg bg-[var(--status-rejected-bg)] px-3 py-2">
                  <p className="text-xs font-medium text-[var(--status-rejected-fg)]">
                    This policy exceeds the gross treaty capacity. A facultative (FAC) cover must be arranged for the excess amount before this allocation can be approved.
                  </p>
                </div>
              )}
            </CardContent>
          </Card>

          {/* Action buttons */}
          {(canConfirm || canCancel) && (
            <div className="flex gap-2 pt-2">
              {canCancel && (
                <Button
                  variant="outline"
                  className="flex-1 text-destructive hover:bg-[var(--status-rejected-bg)]"
                  disabled={cancel.isPending || confirm.isPending}
                  onClick={() => cancel.mutate(allocation.id)}
                >
                  {cancel.isPending ? 'Cancelling…' : 'Cancel allocation'}
                </Button>
              )}
              {canConfirm && (
                <Button
                  className="flex-1"
                  disabled={confirm.isPending || cancel.isPending}
                  onClick={() => confirm.mutate(allocation.id)}
                >
                  {confirm.isPending ? 'Confirming…' : 'Confirm allocation'}
                </Button>
              )}
            </div>
          )}

          {displayStatus === 'EXCESS_CAPACITY' && onCreateFAC && (
            <Button className="w-full" onClick={() => { onOpenChange(false); onCreateFAC(); }}>
              Create FAC Cover
            </Button>
          )}
        </div>
      </SheetContent>
    </Sheet>
  );
}
