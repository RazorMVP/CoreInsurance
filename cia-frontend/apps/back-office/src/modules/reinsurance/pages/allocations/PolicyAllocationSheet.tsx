import {
  Badge, Button, Card, CardContent, CardHeader, CardTitle,
  Separator, Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle,
} from '@cia/ui';

interface Allocation {
  id:              string;
  policyNumber:    string;
  classOfBusiness: string;
  sumInsured:      number;
  retentionAmount: number;
  cedingAmount:    number;
  treatyName:      string;
  treatyType:      string;
  reinsurers:      string;
  status:          'AUTO_ALLOCATED' | 'CONFIRMED' | 'APPROVED' | 'EXCESS_CAPACITY';
  treatyYear:      number;
}

interface Props {
  open:         boolean;
  onOpenChange: (v: boolean) => void;
  allocation:   Allocation | null;
  onConfirm:    (id: string) => void;
  onApprove:    (id: string) => void;
  onReject:     (id: string) => void;
}

// Mock policy detail — replace with useGet(`/api/v1/policies/${policyNumber}`)
function getMockPolicy(policyNumber: string) {
  const policyMap: Record<string, { customer: string; product: string; startDate: string; endDate: string; premium: number }> = {
    'POL-2026-00001': { customer: 'Chioma Okafor',     product: 'Private Motor Comprehensive', startDate: '2026-02-01', endDate: '2027-02-01', premium: 78_750 },
    'POL-2026-00002': { customer: 'Alaba Trading Co.', product: 'Fire & Burglary Standard',    startDate: '2026-03-01', endDate: '2027-03-01', premium: 115_000 },
    'POL-2026-00003': { customer: 'Emeka Eze',         product: 'Private Motor Comprehensive', startDate: '2026-03-15', endDate: '2027-03-15', premium: 49_500 },
    'POL-2026-00004': { customer: 'Alaba Trading Co.', product: 'Marine Cargo Open Cover',     startDate: '2026-01-15', endDate: '2027-01-15', premium: 60_000 },
    'POL-2026-00005': { customer: 'Alaba Trading Co.', product: 'Fire & Burglary Standard',    startDate: '2026-03-01', endDate: '2027-03-01', premium: 195_000 },
  };
  return policyMap[policyNumber] ?? null;
}

const stVariant: Record<Allocation['status'], 'active'|'pending'|'draft'|'rejected'> = {
  AUTO_ALLOCATED: 'draft', CONFIRMED: 'pending', APPROVED: 'active', EXCESS_CAPACITY: 'rejected',
};
const stLabel: Record<Allocation['status'], string> = {
  AUTO_ALLOCATED: 'Auto-allocated', CONFIRMED: 'Confirmed', APPROVED: 'Approved', EXCESS_CAPACITY: 'Excess Capacity',
};

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-start gap-4 py-2" style={{ boxShadow: '0 1px 0 var(--border)' }}>
      <p className="w-36 shrink-0 text-sm text-muted-foreground">{label}</p>
      <p className="text-sm font-medium text-foreground">{value}</p>
    </div>
  );
}

export default function PolicyAllocationSheet({ open, onOpenChange, allocation, onConfirm, onApprove, onReject }: Props) {
  if (!allocation) return null;

  const policy = getMockPolicy(allocation.policyNumber);
  const riRetentionPct  = allocation.sumInsured > 0 ? Math.round(allocation.retentionAmount / allocation.sumInsured * 100) : 0;
  const riCedingPct     = allocation.sumInsured > 0 ? Math.round(allocation.cedingAmount    / allocation.sumInsured * 100) : 0;

  const canConfirm  = allocation.status === 'AUTO_ALLOCATED';
  const canApprove  = allocation.status === 'CONFIRMED';

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="w-full sm:max-w-lg overflow-y-auto">
        <SheetHeader>
          <div className="flex items-center gap-2">
            <SheetTitle>{allocation.policyNumber}</SheetTitle>
            <Badge variant={stVariant[allocation.status]} className="text-[10px]">
              {stLabel[allocation.status]}
            </Badge>
          </div>
          <SheetDescription>
            RI allocation details · {allocation.treatyType} — {allocation.treatyName}
          </SheetDescription>
        </SheetHeader>

        <div className="mt-6 space-y-4">
          {/* Policy details */}
          {policy && (
            <Card>
              <CardHeader><CardTitle>Policy Details</CardTitle></CardHeader>
              <CardContent>
                <Row label="Customer"   value={policy.customer} />
                <Row label="Product"    value={policy.product} />
                <Row label="Class"      value={allocation.classOfBusiness} />
                <Row label="Period"     value={`${policy.startDate} → ${policy.endDate}`} />
                <Row label="Sum Insured" value={`₦${allocation.sumInsured.toLocaleString()}`} />
                <Row label="Premium"    value={`₦${policy.premium.toLocaleString()}`} />
              </CardContent>
            </Card>
          )}

          {/* RI allocation */}
          <Card>
            <CardHeader><CardTitle>Reinsurance Allocation</CardTitle></CardHeader>
            <CardContent>
              <Row label="Treaty"     value={allocation.treatyName} />
              <Row label="Type"       value={allocation.treatyType} />
              <Row label="Reinsurers" value={allocation.reinsurers} />
              <Row label="Year"       value={String(allocation.treatyYear)} />
              <Separator className="my-3" />
              <div className="space-y-3">
                {/* Visual split bar */}
                <div>
                  <div className="flex justify-between text-xs text-muted-foreground mb-1.5">
                    <span>Our Retention ({riRetentionPct}%)</span>
                    <span>Ceded ({riCedingPct}%)</span>
                  </div>
                  <div className="flex h-3 w-full overflow-hidden rounded-full bg-muted">
                    <div
                      className="h-full bg-charcoal-400 rounded-l-full"
                      style={{ width: `${riRetentionPct}%` }}
                    />
                    <div
                      className="h-full bg-primary rounded-r-full flex-1"
                    />
                  </div>
                </div>
                <Row label="Retention"  value={`₦${allocation.retentionAmount.toLocaleString()} (${riRetentionPct}%)`} />
                <Row label="Ceding"     value={`₦${allocation.cedingAmount.toLocaleString()} (${riCedingPct}%)`} />
              </div>

              {allocation.status === 'EXCESS_CAPACITY' && (
                <div className="mt-3 rounded-lg bg-[var(--status-rejected-bg)] px-3 py-2">
                  <p className="text-xs font-medium text-[var(--status-rejected-fg)]">
                    This policy exceeds the gross treaty capacity. A facultative (FAC) cover must be arranged for the excess amount before this allocation can be approved.
                  </p>
                </div>
              )}
            </CardContent>
          </Card>

          {/* Action buttons */}
          {(canConfirm || canApprove) && (
            <div className="flex gap-2 pt-2">
              {canConfirm && (
                <Button className="flex-1" onClick={() => { onConfirm(allocation.id); onOpenChange(false); }}>
                  Confirm Allocation
                </Button>
              )}
              {canApprove && (
                <>
                  <Button
                    variant="outline"
                    className="flex-1 text-destructive hover:bg-[var(--status-rejected-bg)]"
                    onClick={() => { onReject(allocation.id); onOpenChange(false); }}
                  >
                    Decline
                  </Button>
                  <Button className="flex-1" onClick={() => { onApprove(allocation.id); onOpenChange(false); }}>
                    Approve
                  </Button>
                </>
              )}
            </div>
          )}

          {allocation.status === 'EXCESS_CAPACITY' && (
            <Button className="w-full" onClick={() => onOpenChange(false)}>
              Create FAC Cover
            </Button>
          )}
        </div>
      </SheetContent>
    </Sheet>
  );
}
