import {
  Button,
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '@cia/ui';

interface Props {
  open:           boolean;
  onOpenChange:   (v: boolean) => void;
  recipientEmail: string | null;
  documentLabel:  string;       // e.g. "receipt REC-2026-00001"
  isPending:      boolean;
  onConfirm:      () => void;
}

/**
 * Shared confirmation dialog for the Email PDF action. Used by all four
 * F7-γ surfaces:
 *   - ReceiptsListSection (flat receipts list)
 *   - PaymentsListSection (flat payments list)
 *   - DebitNoteDetailDialog (nested receipts per DN)
 *   - CreditNoteDetailDialog (nested payments per CN)
 *
 * The actual mutation lives in the calling component (useEmailReceipt /
 * useEmailPayment); this dialog just confirms the recipient before firing.
 *
 * @since Slice γ — Task 30, F7 email transmission
 */
export default function EmailConfirmDialog({
  open,
  onOpenChange,
  recipientEmail,
  documentLabel,
  isPending,
  onConfirm,
}: Props) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Email {documentLabel}</DialogTitle>
          <DialogDescription>
            The PDF will be sent to{' '}
            <span className="font-medium text-foreground">
              {recipientEmail ?? '(unknown recipient)'}
            </span>{' '}
            via the configured email provider. Delivery happens asynchronously
            — the "Last emailed" badge on the row updates after the workflow
            finishes.
          </DialogDescription>
        </DialogHeader>

        <DialogFooter>
          <Button
            variant="outline"
            onClick={() => onOpenChange(false)}
            disabled={isPending}
          >
            Cancel
          </Button>
          <Button onClick={onConfirm} disabled={isPending || !recipientEmail}>
            {isPending ? 'Sending…' : 'Send email'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
