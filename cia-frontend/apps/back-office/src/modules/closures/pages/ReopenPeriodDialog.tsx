import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import {
  Button,
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
  Label, Textarea,
  useToast,
} from '@cia/ui';
import { validatedPost, PeriodLockDtoSchema, type FiscalPeriodDto } from '@cia/api-client';

interface ReopenPeriodDialogProps {
  period: FiscalPeriodDto | null;
  open:   boolean;
  onOpenChange: (open: boolean) => void;
}

export default function ReopenPeriodDialog({ period, open, onOpenChange }: ReopenPeriodDialogProps) {
  const [reason, setReason] = useState('');
  const queryClient = useQueryClient();
  const { toast } = useToast();

  const reopenMutation = useMutation({
    mutationFn: () => {
      if (!period) throw new Error('No period selected');
      return validatedPost(
        `/api/v1/finance/period-locks/${period.id}/reopen`,
        { reason: reason.trim() },
        PeriodLockDtoSchema,
      );
    },
    onSuccess: () => {
      toast({
        title: 'Period reopened',
        description: `The active lock on ${period?.startDate} → ${period?.endDate} has been released. CFO + compliance distribution notified.`,
      });
      queryClient.invalidateQueries({ queryKey: ['closures', 'periods'] });
      queryClient.invalidateQueries({ queryKey: ['closures', 'history', period?.id] });
      setReason('');
      onOpenChange(false);
    },
    onError: (err: unknown) => {
      const msg = err instanceof Error ? err.message : 'Request failed';
      toast({ title: 'Reopen failed', description: msg, variant: 'destructive' });
    },
  });

  function handleClose(nextOpen: boolean) {
    if (!nextOpen) setReason('');
    onOpenChange(nextOpen);
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (reason.trim().length === 0) return;
    reopenMutation.mutate();
  }

  const disabled = reason.trim().length === 0 || reopenMutation.isPending;

  return (
    <Dialog open={open} onOpenChange={handleClose}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>Reopen period</DialogTitle>
          <DialogDescription>
            Releases the active lock. Publishes <code className="font-mono text-xs">PeriodReopenedEvent</code> — CFO + compliance email distribution will be notified. The reason will appear in the audit log and the next NAICOM year-end review.
          </DialogDescription>
        </DialogHeader>
        <form onSubmit={handleSubmit} className="space-y-3">
          {period && (
            <div className="rounded-md border bg-amber-50 px-3 py-2 text-sm">
              <div className="text-xs uppercase tracking-wide text-amber-700">Closed period</div>
              <div className="font-mono">{period.periodType} · {period.startDate} → {period.endDate}</div>
              <div className="mt-1 text-xs text-amber-700">Current status: {period.status}</div>
            </div>
          )}
          <div className="space-y-1.5">
            <Label htmlFor="reopen-reason">Reason <span className="text-destructive">*</span></Label>
            <Textarea
              id="reopen-reason"
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder="Why is this period being reopened? (mandatory for NAICOM review, max 500 chars)"
              rows={4}
              maxLength={500}
              autoFocus
            />
            <div className="text-xs text-muted-foreground">{reason.length} / 500</div>
          </div>
          <DialogFooter className="gap-2">
            <Button type="button" variant="outline" onClick={() => handleClose(false)} disabled={reopenMutation.isPending}>
              Cancel
            </Button>
            <Button type="submit" disabled={disabled}>
              {reopenMutation.isPending ? 'Reopening…' : 'Reopen period'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
