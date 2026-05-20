import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import {
  Button,
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
  Label, Textarea,
  useToast,
} from '@cia/ui';
import { validatedPost, PeriodLockDtoSchema, type FiscalPeriodDto } from '@cia/api-client';

type Mode = 'SOFT' | 'HARD';

interface ClosePeriodDialogProps {
  period: FiscalPeriodDto | null;
  mode:   Mode;
  open:   boolean;
  onOpenChange: (open: boolean) => void;
}

const MODE_COPY: Record<Mode, { title: string; description: string; button: string; queryParam: string }> = {
  SOFT: {
    title:       'Soft-close period',
    description: 'Opens the 5-business-day grace window. Reads and reversals continue to flow; new writes require FINANCE_OVERRIDE_LOCK.',
    button:      'Soft-close',
    queryParam:  'soft-close',
  },
  HARD: {
    title:       'Hard-close period',
    description: 'Terminal close. All writes (including reversals) are blocked until the period is reopened by a user with FINANCE_REOPEN_PERIOD.',
    button:      'Hard-close',
    queryParam:  'hard-close',
  },
};

export default function ClosePeriodDialog({ period, mode, open, onOpenChange }: ClosePeriodDialogProps) {
  const copy = MODE_COPY[mode];
  const [reason, setReason] = useState('');
  const queryClient = useQueryClient();
  const { toast } = useToast();

  const closeMutation = useMutation({
    mutationFn: () => {
      if (!period) throw new Error('No period selected');
      return validatedPost(
        `/api/v1/finance/period-locks/${period.id}/${copy.queryParam}`,
        { reason: reason.trim() },
        PeriodLockDtoSchema,
      );
    },
    onSuccess: () => {
      toast({ title: `${copy.title} succeeded`, description: `Period ${period?.startDate} → ${period?.endDate} is now ${mode === 'SOFT' ? 'SOFT_CLOSED' : 'HARD_CLOSED'}.` });
      queryClient.invalidateQueries({ queryKey: ['closures', 'periods'] });
      queryClient.invalidateQueries({ queryKey: ['closures', 'history', period?.id] });
      setReason('');
      onOpenChange(false);
    },
    onError: (err: unknown) => {
      const msg = err instanceof Error ? err.message : 'Request failed';
      toast({ title: `${copy.button} failed`, description: msg, variant: 'destructive' });
    },
  });

  function handleClose(nextOpen: boolean) {
    if (!nextOpen) setReason('');
    onOpenChange(nextOpen);
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (reason.trim().length === 0) return;
    closeMutation.mutate();
  }

  const disabled = reason.trim().length === 0 || closeMutation.isPending;

  return (
    <Dialog open={open} onOpenChange={handleClose}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>{copy.title}</DialogTitle>
          <DialogDescription>{copy.description}</DialogDescription>
        </DialogHeader>
        <form onSubmit={handleSubmit} className="space-y-3">
          {period && (
            <div className="rounded-md border bg-muted/40 px-3 py-2 text-sm">
              <div className="text-muted-foreground text-xs uppercase tracking-wide">Period</div>
              <div className="font-mono">{period.periodType} · {period.startDate} → {period.endDate}</div>
            </div>
          )}
          <div className="space-y-1.5">
            <Label htmlFor="close-reason">Reason <span className="text-destructive">*</span></Label>
            <Textarea
              id="close-reason"
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder="Why is this period being closed? (audit trail, max 500 chars)"
              rows={4}
              maxLength={500}
              autoFocus
            />
            <div className="text-xs text-muted-foreground">{reason.length} / 500</div>
          </div>
          <DialogFooter className="gap-2">
            <Button type="button" variant="outline" onClick={() => handleClose(false)} disabled={closeMutation.isPending}>
              Cancel
            </Button>
            <Button type="submit" disabled={disabled} variant={mode === 'HARD' ? 'destructive' : 'default'}>
              {closeMutation.isPending ? 'Submitting…' : copy.button}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
