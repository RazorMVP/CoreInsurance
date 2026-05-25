import { useState } from 'react';
import {
  Badge, Button, Separator, Skeleton,
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '@cia/ui';
import { useQuery } from '@tanstack/react-query';
import { apiClient, type DebitNoteDto, type PolicyDto } from '@cia/api-client';
import { useReceiptList } from '../../hooks/useReceipts';
import ReverseTransactionDialog, { type ReverseTarget } from '../ReverseTransactionDialog';

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

  const [reverseTarget, setReverseTarget] = useState<ReverseTarget | null>(null);
  const receiptsQuery = useReceiptList(
    debitNote ? { debitNoteId: debitNote.id } : { debitNoteId: '' },
  );
  const receipts = receiptsQuery.data?.data ?? [];

  if (!debitNote) return null;

  const canPost = debitNote.status === 'OUTSTANDING' || debitNote.status === 'PARTIAL';

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-lg">
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
                <DetailRow label="Period" value={`${policyQuery.data.policyStartDate} → ${policyQuery.data.policyEndDate}`} />
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

        {receipts.length > 0 && (
          <section className="mt-2 rounded-lg border overflow-hidden">
            <div className="bg-muted/40 px-4 py-2 flex items-center justify-between">
              <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                Receipts ({receipts.length})
              </p>
            </div>
            <ul className="divide-y">
              {receipts.map((r) => (
                <li key={r.id} className="flex items-start justify-between gap-3 px-4 py-2">
                  <div className="flex min-w-0 flex-col gap-0.5">
                    <span className="font-mono text-xs">{r.reference}</span>
                    <span className="text-xs text-muted-foreground">
                      ₦{r.amount.toLocaleString()} · {r.paymentMethod.replace('_', ' ').toLowerCase()} · {r.paymentDate ?? '—'}
                    </span>
                    {r.status === 'REVERSED' && r.reversedAt && (
                      <span className="text-[11px] text-muted-foreground">
                        Reversed {new Date(r.reversedAt).toLocaleString()} by {r.reversedBy ?? 'unknown'}
                        {r.reversalReason ? ` — ${r.reversalReason}` : ''}
                      </span>
                    )}
                  </div>
                  <div className="flex shrink-0 items-start gap-2">
                    <Badge
                      variant={r.status === 'POSTED' ? 'active' : 'rejected'}
                      className="text-[10px]"
                    >
                      {r.status.toLowerCase()}
                    </Badge>
                    {r.status === 'POSTED' && (
                      <Button
                        variant="outline"
                        size="sm"
                        onClick={() => setReverseTarget({
                          type:      'RECEIPT',
                          id:        r.id,
                          parentId:  r.debitNoteId,
                          reference: r.reference,
                          linkedRef: r.debitNoteNumber,
                          amount:    r.amount,
                          method:    r.paymentMethod,
                          date:      r.paymentDate ?? '',
                        })}
                      >
                        Reverse
                      </Button>
                    )}
                  </div>
                </li>
              ))}
            </ul>
          </section>
        )}

        <ReverseTransactionDialog
          open={reverseTarget !== null}
          onOpenChange={(v) => { if (!v) setReverseTarget(null); }}
          target={reverseTarget}
        />

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
