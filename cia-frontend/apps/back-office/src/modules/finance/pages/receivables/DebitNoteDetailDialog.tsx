import {
  Badge, Button, Separator, Skeleton,
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '@cia/ui';
import { useQuery } from '@tanstack/react-query';
import { apiClient, type DebitNoteDto, type PolicyDto } from '@cia/api-client';

const DN_STATUS_VARIANT: Record<DebitNoteDto['status'], 'pending' | 'active' | 'draft' | 'rejected'> = {
  OUTSTANDING: 'pending',
  PARTIAL:     'draft',
  SETTLED:     'active',
  CANCELLED:   'rejected',
  VOID:        'rejected',
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
  // Look up policy for class + period when this debit note is policy-backed.
  // DebitNoteResponse already carries productName + description, so this query
  // fills in only what the debit note itself doesn't expose.
  const isPolicyBacked = debitNote?.entityType === 'POLICY';
  const policyQuery = useQuery<PolicyDto>({
    queryKey: ['policies', debitNote?.entityId],
    queryFn: async () => {
      const res = await apiClient.get<{ data: PolicyDto }>(`/api/v1/policies/${debitNote!.entityId}`);
      return res.data.data;
    },
    enabled: open && isPolicyBacked && !!debitNote?.entityId,
  });

  if (!debitNote) return null;

  const canPost = debitNote.status === 'OUTSTANDING' || debitNote.status === 'PARTIAL';

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <div className="flex items-center gap-2">
            <DialogTitle>{debitNote.debitNoteNumber}</DialogTitle>
            <Badge variant={DN_STATUS_VARIANT[debitNote.status]} className="text-[10px]">
              {debitNote.status.toLowerCase()}
            </Badge>
          </div>
          <DialogDescription>
            Review the policy and debit note details before posting a receipt.
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-0 rounded-lg border overflow-hidden">
          <div className="bg-muted/40 px-4 py-2">
            <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
              {isPolicyBacked ? 'Policy' : 'Source'}
            </p>
          </div>
          <div className="px-4 pb-2">
            <DetailRow label={isPolicyBacked ? 'Policy No.' : 'Reference'} value={debitNote.entityReference} />
            <DetailRow label="Customer"  value={debitNote.customerName} />
            <DetailRow label="Product"   value={debitNote.productName} />
            {debitNote.description && (
              <DetailRow label="Description" value={debitNote.description} />
            )}
            {isPolicyBacked && policyQuery.isLoading && (
              <div className="py-2"><Skeleton className="h-4 w-48" /></div>
            )}
            {isPolicyBacked && policyQuery.data && (
              <>
                <DetailRow label="Class"  value={policyQuery.data.classOfBusinessName} />
                <DetailRow label="Period" value={`${policyQuery.data.startDate} → ${policyQuery.data.endDate}`} />
              </>
            )}
          </div>

          <Separator />

          <div className="bg-muted/40 px-4 py-2">
            <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">Debit Note</p>
          </div>
          <div className="px-4 pb-2">
            <DetailRow label="Debit Note"   value={debitNote.debitNoteNumber} />
            <DetailRow label="Due Date"     value={debitNote.dueDate} />
            <DetailRow label="Total"        value={`₦${debitNote.totalAmount.toLocaleString()}`} />
            <DetailRow label="Paid"         value={`₦${debitNote.paidAmount.toLocaleString()}`} />
          </div>
          <div className="bg-muted/40 px-4 py-3 flex items-center justify-between">
            <p className="text-sm font-semibold">Outstanding</p>
            <p className="text-base font-semibold text-primary">₦{debitNote.outstandingAmount.toLocaleString()}</p>
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
