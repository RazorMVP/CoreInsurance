import { useEffect } from 'react';
import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button, Form, FormControl, FormField, FormItem, FormLabel, FormMessage, FormRow,
  Input, Separator,
  Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle,
} from '@cia/ui';
import { useForm } from 'react-hook-form';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { apiClient } from '@cia/api-client';
import { z } from 'zod';

interface InwardFAC {
  id:             string;
  reference:      string;
  cedingCompany:  string;
  classOfBusiness:string;
  sumInsured:     number;
  ourShare:       number;
  ourPremium:     number;
  startDate:      string;
  endDate:        string;
  status:         string;
}

const schema = z.object({
  newStartDate: z.string().optional(),
  newEndDate:   z.string().min(1, 'New end date is required'),
  ourShare:     z.coerce.number().min(0.1, 'Must be at least 0.1%').max(100),
  premiumRate:  z.coerce.number().min(0),
  notes:        z.string().optional(),
});
type FormValues = z.infer<typeof schema>;

export type InwardFACMode = 'RENEW' | 'EXTEND';

interface Props {
  open:         boolean;
  onOpenChange: (v: boolean) => void;
  fac:          InwardFAC | null;
  mode:         InwardFACMode;
  onSuccess:    () => void;
}

function addYears(dateStr: string, years: number): string {
  const d = new Date(dateStr);
  d.setFullYear(d.getFullYear() + years);
  return d.toISOString().split('T')[0];
}

function addMonths(dateStr: string, months: number): string {
  const d = new Date(dateStr);
  d.setMonth(d.getMonth() + months);
  return d.toISOString().split('T')[0];
}

function addDays(dateStr: string, days: number): string {
  const d = new Date(dateStr);
  d.setDate(d.getDate() + days);
  return d.toISOString().split('T')[0];
}

function impliedRate(fac: InwardFAC): number {
  const ourSI = fac.sumInsured * (fac.ourShare / 100);
  if (ourSI === 0) return 0;
  return Math.round((fac.ourPremium / ourSI) * 10000) / 100;
}

function DetailRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-start gap-4 py-1.5" style={{ boxShadow: '0 1px 0 var(--border)' }}>
      <p className="w-36 shrink-0 text-xs text-muted-foreground">{label}</p>
      <p className="text-xs font-medium text-foreground">{value}</p>
    </div>
  );
}

export default function InwardFACActionSheet({ open, onOpenChange, fac, mode, onSuccess }: Props) {
  const isRenew = mode === 'RENEW';

  const form = useForm<FormValues>({
    resolver: zodResolver(schema) as any,
    defaultValues: {
      newStartDate: '',
      newEndDate:   '',
      ourShare:     0,
      premiumRate:  0,
      notes:        '',
    },
  });

  useEffect(() => {
    if (open && fac) {
      form.reset({
        newStartDate: isRenew ? addDays(fac.endDate, 1) : fac.startDate,
        newEndDate:   isRenew ? addYears(fac.endDate, 1) : addMonths(fac.endDate, 6),
        ourShare:     fac.ourShare,
        premiumRate:  impliedRate(fac),
        notes:        '',
      });
    }
  }, [open, fac?.id, mode]);

  const ourShare    = form.watch('ourShare')   || 0;
  const premiumRate = form.watch('premiumRate') || 0;
  const ourSI       = fac ? (fac.sumInsured * ourShare) / 100 : 0;
  const ourPremium  = (ourSI * premiumRate) / 100;

  const queryClient = useQueryClient();
  const action = useMutation({
    mutationFn: async (values: FormValues) => {
      const verb = mode === 'RENEW' ? 'renew' : 'extend';
      const res = await apiClient.post<{ data: { id: string } }>(
        `/api/v1/reinsurance/fac/inward/${fac?.id}/${verb}`,
        values,
      );
      return res.data.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['reinsurance', 'fac'] });
      onSuccess();
    },
  });

  function onSubmit(values: FormValues) {
    action.mutate(values);
  }

  if (!fac) return null;

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="w-full sm:max-w-lg overflow-y-auto">
        <SheetHeader>
          <SheetTitle>{isRenew ? 'Renew Inward FAC' : 'Extend Inward FAC Period'}</SheetTitle>
          <SheetDescription>
            {isRenew
              ? 'Create a renewal for the next period. Amend share percentage or premium rate as agreed with the ceding company.'
              : 'Extend the cover period. Amend terms as needed before confirming with the ceding company.'}
          </SheetDescription>
        </SheetHeader>

        {/* Current cover summary */}
        <div className="mt-4 rounded-lg border overflow-hidden">
          <div className="bg-muted/40 px-4 py-2">
            <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
              Current Cover — {fac.reference}
            </p>
          </div>
          <div className="px-4 pb-3">
            <DetailRow label="Ceding Company"  value={fac.cedingCompany} />
            <DetailRow label="Class"           value={fac.classOfBusiness} />
            <DetailRow label="Sum Insured"     value={`₦${fac.sumInsured.toLocaleString()}`} />
            <DetailRow label="Our Share"       value={`${fac.ourShare}%`} />
            <DetailRow label="Our Premium"     value={`₦${fac.ourPremium.toLocaleString()}`} />
            <DetailRow label="Period"          value={`${fac.startDate} → ${fac.endDate}`} />
          </div>
        </div>

        <Form {...form}>
          <form onSubmit={form.handleSubmit(onSubmit)} className="mt-4 space-y-4">
            <Separator />
            <p className="text-sm font-semibold text-foreground">{isRenew ? 'Renewal' : 'Extension'} Period</p>

            {isRenew ? (
              <FormRow>
                <FormField control={form.control} name="newStartDate"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>New Start Date</FormLabel>
                      <FormControl><Input type="date" {...field} /></FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />
                <FormField control={form.control} name="newEndDate"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>New End Date</FormLabel>
                      <FormControl><Input type="date" {...field} /></FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />
              </FormRow>
            ) : (
              <FormField control={form.control} name="newEndDate"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>New End Date</FormLabel>
                    <FormControl><Input type="date" {...field} /></FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
            )}

            <Separator />
            <p className="text-sm font-semibold text-foreground">Amended Terms</p>

            <FormRow>
              <FormField control={form.control} name="ourShare"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Our Share (%)</FormLabel>
                    <FormControl>
                      <Input type="number" min={0.1} max={100} step={0.5} {...field} />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
              <FormField control={form.control} name="premiumRate"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Premium Rate (%)</FormLabel>
                    <FormControl>
                      <Input type="number" min={0} step={0.01} {...field} />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
            </FormRow>

            {fac.sumInsured > 0 && ourShare > 0 && (
              <div className="rounded-lg bg-muted/40 p-3 space-y-1 text-xs">
                <div className="flex justify-between">
                  <span className="text-muted-foreground">
                    Our SI ({ourShare}% of ₦{fac.sumInsured.toLocaleString()})
                  </span>
                  <span className="font-medium">₦{ourSI.toLocaleString()}</span>
                </div>
                {premiumRate > 0 && (
                  <div className="flex justify-between">
                    <span className="text-muted-foreground">Our Premium ({premiumRate}%)</span>
                    <span className="font-medium text-primary">
                      ₦{ourPremium.toLocaleString(undefined, { minimumFractionDigits: 2 })}
                    </span>
                  </div>
                )}
              </div>
            )}

            <FormField control={form.control} name="notes"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Notes (optional)</FormLabel>
                  <FormControl>
                    <Input placeholder="Reason for renewal / extension or agreed changes" {...field} />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

            <SheetFooter className="pt-2">
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
              <Button type="submit" disabled={form.formState.isSubmitting}>
                {form.formState.isSubmitting
                  ? 'Saving…'
                  : isRenew ? 'Confirm Renewal' : 'Confirm Extension'}
              </Button>
            </SheetFooter>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  );
}
