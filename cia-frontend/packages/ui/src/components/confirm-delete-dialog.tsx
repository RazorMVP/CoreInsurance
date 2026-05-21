import * as React from 'react';
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from './dialog';
import { Button } from './button';
import { Label } from './label';
import { Textarea } from './textarea';

/**
 * Standard delete-confirmation dialog used across the back-office for any
 * soft-delete action that should be auditor-traceable. Captures a required
 * `reason` and passes it to the caller's mutation, which is expected to
 * pass it through to a DELETE endpoint as `?reason=...`. The backend's
 * AuditService.logWithReason records it on audit_log.reason (V47).
 *
 * Soft-delete only — the underlying row stays in the database with
 * deleted_at set; auditors can extract it and trace who/when/why.
 */
export interface ConfirmDeleteDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Pretty label of the entity being deleted, e.g. "Broker" or "Class of Business". */
  entityLabel: string;
  /** Human-friendly identifier of the row (name, code, customer number, etc.). */
  entityName?: string;
  /** Called when user confirms with a non-blank reason. Caller fires the DELETE mutation. */
  onConfirm: (reason: string) => void;
  /** Disable the confirm button (typically while the delete mutation is in flight). */
  busy?: boolean;
}

export function ConfirmDeleteDialog({
  open, onOpenChange, entityLabel, entityName, onConfirm, busy,
}: ConfirmDeleteDialogProps) {
  const [reason, setReason] = React.useState('');

  // Reset reason when the dialog opens/closes so re-opens don't carry state
  React.useEffect(() => { if (!open) setReason(''); }, [open]);

  const trimmed = reason.trim();
  const canSubmit = trimmed.length > 0 && !busy;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Delete {entityLabel}{entityName ? ` — ${entityName}` : ''}?</DialogTitle>
          <DialogDescription>
            This is a soft delete. The record stays in the database; auditors can
            extract it with the timestamp, the user, and the reason below. Provide
            a reason to continue.
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-2 py-2">
          <Label htmlFor="confirm-delete-reason">Reason for deletion <span className="text-destructive">*</span></Label>
          <Textarea
            id="confirm-delete-reason"
            rows={3}
            placeholder="e.g. Duplicate record; created in error; superseded by …"
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            autoFocus
          />
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={busy}>Cancel</Button>
          <Button
            variant="destructive"
            disabled={!canSubmit}
            onClick={() => onConfirm(trimmed)}
          >
            {busy ? 'Deleting…' : 'Delete'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
