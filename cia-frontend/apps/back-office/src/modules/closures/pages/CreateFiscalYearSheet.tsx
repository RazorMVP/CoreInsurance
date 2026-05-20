import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import {
  Button,
  Input,
  Label,
  Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
  useToast,
} from '@cia/ui';
import {
  validatedPost,
  FiscalYearDtoSchema,
  type FiscalYearDto,
} from '@cia/api-client';

interface CreateFiscalYearSheetProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onCreated?: (fy: FiscalYearDto) => void;
}

function defaultDates() {
  const year = new Date().getFullYear();
  return {
    start: `${year}-01-01`,
    end:   `${year}-12-31`,
  };
}

export default function CreateFiscalYearSheet({ open, onOpenChange, onCreated }: CreateFiscalYearSheetProps) {
  const defaults = defaultDates();
  const [name,      setName]      = useState('');
  const [startDate, setStartDate] = useState(defaults.start);
  const [endDate,   setEndDate]   = useState(defaults.end);
  const queryClient = useQueryClient();
  const { toast } = useToast();

  const createMutation = useMutation({
    mutationFn: () => validatedPost(
      '/api/v1/finance/fiscal-years',
      {
        name:      name.trim() || undefined,
        startDate: startDate || undefined,
        endDate:   endDate || undefined,
      },
      FiscalYearDtoSchema,
    ),
    onSuccess: (fy) => {
      toast({
        title: 'Fiscal year created',
        description: `${fy.name} (${fy.startDate} → ${fy.endDate}). 19 periods auto-generated. Status: ${fy.status}.`,
      });
      queryClient.invalidateQueries({ queryKey: ['closures', 'fiscal-years'] });
      queryClient.invalidateQueries({ queryKey: ['closures', 'periods'] });
      reset();
      onCreated?.(fy);
      onOpenChange(false);
    },
    onError: (err: unknown) => {
      const msg = err instanceof Error ? err.message : 'Request failed';
      toast({ title: 'Create failed', description: msg, variant: 'destructive' });
    },
  });

  function reset() {
    setName('');
    const d = defaultDates();
    setStartDate(d.start);
    setEndDate(d.end);
  }

  function handleClose(nextOpen: boolean) {
    if (!nextOpen) reset();
    onOpenChange(nextOpen);
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    createMutation.mutate();
  }

  const derivedName = name.trim() || (startDate ? `FY${new Date(startDate).getFullYear()}` : 'FY{year}');

  return (
    <Sheet open={open} onOpenChange={handleClose}>
      <SheetContent className="w-full sm:max-w-md">
        <SheetHeader>
          <SheetTitle>Create fiscal year</SheetTitle>
          <SheetDescription>
            Creates the fiscal year in PLANNING state and synchronously generates 19 periods (12 month + 4 quarter + 2 half-year + 1 year). DAY periods are lazy. Activation is a separate step.
          </SheetDescription>
        </SheetHeader>
        <form onSubmit={handleSubmit} className="mt-6 space-y-4">
          <div className="space-y-1.5">
            <Label htmlFor="fy-name">Name</Label>
            <Input
              id="fy-name"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder={derivedName}
              maxLength={50}
              autoFocus
            />
            <div className="text-xs text-muted-foreground">
              Leave blank to use <span className="font-mono">{derivedName}</span>.
            </div>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div className="space-y-1.5">
              <Label htmlFor="fy-start">Start date</Label>
              <Input
                id="fy-start"
                type="date"
                value={startDate}
                onChange={(e) => setStartDate(e.target.value)}
              />
              <div className="text-xs text-muted-foreground">First day of a month.</div>
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="fy-end">End date</Label>
              <Input
                id="fy-end"
                type="date"
                value={endDate}
                onChange={(e) => setEndDate(e.target.value)}
              />
              <div className="text-xs text-muted-foreground">Last day of a month.</div>
            </div>
          </div>
          <div className="rounded-md border bg-muted/40 px-3 py-2.5 text-xs">
            <div className="font-medium text-foreground">After creation</div>
            <ul className="mt-1 list-disc space-y-0.5 pl-4 text-muted-foreground">
              <li>The new year will appear in PLANNING. Use Activate to switch the tenant's current FY.</li>
              <li>Activating demotes any currently ACTIVE year — there is exactly one ACTIVE FY per tenant.</li>
              <li>Period locks start OPEN. Use the period table to soft-close / hard-close.</li>
            </ul>
          </div>
          <SheetFooter className="gap-2">
            <Button type="button" variant="outline" onClick={() => handleClose(false)} disabled={createMutation.isPending}>
              Cancel
            </Button>
            <Button type="submit" disabled={createMutation.isPending}>
              {createMutation.isPending ? 'Creating…' : 'Create fiscal year'}
            </Button>
          </SheetFooter>
        </form>
      </SheetContent>
    </Sheet>
  );
}
