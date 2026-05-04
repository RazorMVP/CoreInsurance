import { useEffect, useState } from 'react';
import {
  Button,
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
  Label, Textarea,
  toast,
} from '@cia/ui';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { apiClient, type ApiError, type ApiResponse } from '@cia/api-client';

interface ApiHttpError { response?: { data?: ApiResponse<unknown> }; message?: string }

export interface ReverseTarget {
  type:       'RECEIPT' | 'PAYMENT';
  id:         string;   // receipt or payment UUID
  parentId:   string;   // debit-note UUID (RECEIPT) or credit-note UUID (PAYMENT)
  reference:  string;   // REC-xxx or PAY-xxx
  linkedRef:  string;   // debit note number or credit note number
  amount:     number;
  method:     string;
  date:       string;
}

interface Props {
  open:         boolean;
  onOpenChange: (v: boolean) => void;
  target:       ReverseTarget | null;
}

function DetailRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-start gap-4 py-2" style={{ boxShadow: '0 1px 0 var(--border)' }}>
      <p className="w-36 shrink-0 text-sm text-muted-foreground">{label}</p>
      <p className="text-sm font-medium text-foreground">{value}</p>
    </div>
  );
}

export default function ReverseTransactionDialog({ open, onOpenChange, target }: Props) {
  const queryClient = useQueryClient();
  const [reason, setReason]         = useState('');
  const [reasonError, setReasonErr] = useState<string | null>(null);

  // Reset state when dialog opens/closes or target changes
  useEffect(() => {
    if (!open) { setReason(''); setReasonErr(null); }
  }, [open]);

  const reverse = useMutation({
    mutationFn: async ({ url }: { url: string }) => {
      await apiClient.post(url, { reason });
    },
    onSuccess: () => {
      if (!target) return;
      const isReceipt = target.type === 'RECEIPT';
      queryClient.invalidateQueries({
        queryKey: ['finance', isReceipt ? 'receipts' : 'payments'],
      });
      queryClient.invalidateQueries({
        queryKey: ['finance', isReceipt ? 'debit-notes' : 'credit-notes'],
      });
      toast({
        title: isReceipt ? 'Receipt reversed' : 'Payment reversed',
        description: `${target.reference} marked as reversed. ${target.linkedRef} is now outstanding.`,
      });
      onOpenChange(false);
    },
    onError: (error) => {
      // Map reason-field errors inline; everything else surfaces as a toast.
      const ax = error as ApiHttpError;
      const errors: ApiError[] = ax?.response?.data?.errors ?? [];
      const reasonErr = errors.find(e => e.field === 'reason');
      if (reasonErr) {
        setReasonErr(reasonErr.message);
        return;
      }
      const description = errors.length > 0
        ? errors.map(e => e.message).filter(Boolean).join('. ')
        : ax?.message ?? 'An unexpected error occurred. Please try again.';
      toast({ variant: 'destructive', title: 'Reversal failed', description });
    },
  });

  if (!target) return null;

  const isReceipt   = target.type === 'RECEIPT';
  const title       = isReceipt ? 'Reverse Receipt' : 'Reverse Payment';
  const linkedLabel = isReceipt ? 'Debit Note' : 'Credit Note';

  function handleConfirm() {
    if (!reason.trim()) {
      setReasonErr('Reason is required.');
      return;
    }
    if (!target) return;
    const url = isReceipt
      ? `/api/v1/debit-notes/${target.parentId}/receipts/${target.id}/reverse`
      : `/api/v1/credit-notes/${target.parentId}/payments/${target.id}/reverse`;
    reverse.mutate({ url });
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-sm">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription>
            Review the details below before confirming. This action cannot be undone.
          </DialogDescription>
        </DialogHeader>

        <div className="rounded-lg border overflow-hidden">
          <div className="bg-muted/40 px-4 py-2">
            <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
              {isReceipt ? 'Receipt' : 'Payment'} Details
            </p>
          </div>
          <div className="px-4 pb-2">
            <DetailRow label={isReceipt ? 'Receipt No.' : 'Payment No.'} value={target.reference} />
            <DetailRow label={linkedLabel}  value={target.linkedRef} />
            <DetailRow label="Method"       value={target.method} />
            <DetailRow label="Date"         value={target.date} />
          </div>
          <div className="bg-muted/40 px-4 py-3 flex items-center justify-between">
            <p className="text-sm font-semibold">Amount</p>
            <p className="text-base font-semibold">₦{target.amount.toLocaleString()}</p>
          </div>
        </div>

        {/* Reason */}
        <div className="space-y-1.5">
          <Label htmlFor="reverse-reason">Reason for reversal</Label>
          <Textarea
            id="reverse-reason"
            placeholder="e.g. Bounced cheque / posted in error / customer request"
            value={reason}
            onChange={e => { setReason(e.target.value); if (reasonError) setReasonErr(null); }}
            rows={3}
            disabled={reverse.isPending}
          />
          {reasonError && <p className="text-xs text-destructive">{reasonError}</p>}
        </div>

        {/* Cannot be undone warning */}
        <div className="rounded-lg bg-[var(--status-rejected-bg)] px-4 py-3">
          <p className="text-xs font-semibold text-[var(--status-rejected-fg)] mb-1">This cannot be undone</p>
          <p className="text-xs text-[var(--status-rejected-fg)]/80">
            {isReceipt
              ? 'Reversing this receipt will mark the debit note as Outstanding again. The customer will need to make a new payment.'
              : 'Reversing this payment will mark the credit note as Outstanding again. A new payment will need to be processed.'}
          </p>
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={reverse.isPending}>Cancel</Button>
          <Button variant="destructive" onClick={handleConfirm} disabled={reverse.isPending}>
            {reverse.isPending ? 'Reversing…' : 'Confirm Reversal'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
