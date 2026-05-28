import {
  Button,
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '@cia/ui';

import { formatPhone } from '../lib/formatPhone';

interface Props {
  open:           boolean;
  onOpenChange:   (v: boolean) => void;
  recipientPhone: string | null;
  documentLabel:  string;       // e.g. "receipt REC-2026-00001"
  isPending:      boolean;
  onConfirm:      () => void;
}

/**
 * Shared confirmation dialog for the Send SMS action. Used by all four
 * F7-δ surfaces:
 *   - ReceiptsListSection (flat receipts list)
 *   - PaymentsListSection (flat payments list)
 *   - DebitNoteDetailDialog (nested receipts per DN)
 *   - CreditNoteDetailDialog (nested payments per CN)
 *
 * The actual mutation lives in the calling component (useSmsReceipt /
 * useSmsPayment); this dialog just confirms the recipient before firing.
 *
 * Mirror of EmailConfirmDialog (F7-γ).
 *
 * @since Slice δ — F7 SMS transmission
 */
export default function SmsConfirmDialog({
  open,
  onOpenChange,
  recipientPhone,
  documentLabel,
  isPending,
  onConfirm,
}: Props) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Send {documentLabel} by SMS</DialogTitle>
          <DialogDescription>
            An SMS notification will be sent to{' '}
            <span className="font-medium text-foreground">
              {recipientPhone ? formatPhone(recipientPhone) : '(unknown recipient)'}
            </span>{' '}
            via the configured SMS provider. Delivery happens asynchronously
            — the "Last texted" badge on the row updates after the workflow
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
          <Button onClick={onConfirm} disabled={isPending || !recipientPhone}>
            {isPending ? 'Sending…' : 'Send SMS'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
